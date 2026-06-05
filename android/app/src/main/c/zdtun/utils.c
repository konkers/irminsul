/* ----------------------------------------------------------------------------
 * Zero Dep Tunnel: VPN library without dependencies
 * ----------------------------------------------------------------------------
 *
 * Copyright (C) 2018 - Emanuele Faranda
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 *
 */

#include "zdtun.h"
#include "utils.h"

#include <stdarg.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <sys/ioctl.h>

//#define DEBUG_COMMUNICATION

// NOTE: xor is not safe!
#define ENCODE_KEY "?!?0QAxAW1e.^9KJdma(//n PQe["
#define TUN_MTU 1500

/* ******************************************************* */

static socket_t server_init(const char *address, int listen_port) {
  socket_t listen_sock;

  if((listen_sock = socket(AF_INET, SOCK_STREAM, 0)) == INVALID_SOCKET)
    fatal("could not create socket[%d]", socket_errno);

  if(setsockopt(listen_sock, SOL_SOCKET, SO_REUSEADDR, (char*)(&(int){ 1 }), sizeof(int)) == SOCKET_ERROR)
    fatal("setsockopt(SO_REUSEADDR) failed[%d]", socket_errno);

  struct sockaddr_in server_address;
  memset(&server_address, 0, sizeof(server_address));
  server_address.sin_family = AF_INET;

  inet_pton(AF_INET, address, &server_address.sin_addr);
  server_address.sin_port = htons(listen_port);

  if ((bind(listen_sock, (struct sockaddr *)&server_address,
            sizeof(server_address))) == SOCKET_ERROR)
    fatal("could not bind the socket [%d]", socket_errno);

  int wait_size = 1;

  if(listen(listen_sock, wait_size) == SOCKET_ERROR)
    fatal("socket listen error [%d]", socket_errno);

  log("Listening on port %d", listen_port);

  return listen_sock;
}

/* ******************************************************* */

static socket_t client_init(const char *server_name, int server_port, struct sockaddr_in *srv_addr) {
  struct sockaddr_in server_address;
  memset(&server_address, 0, sizeof(server_address));
  server_address.sin_family = AF_INET;

  inet_pton(AF_INET, server_name, &server_address.sin_addr);
  server_address.sin_port = htons(server_port);

  socket_t sock;
  if((sock = socket(AF_INET, SOCK_STREAM, 0)) == INVALID_SOCKET)
    fatal("could not create socket[%d]", sock);

  if(connect(sock, (struct sockaddr*)&server_address, sizeof(server_address)) == SOCKET_ERROR)
    fatal("could not connect to the server[%d]", socket_errno);

  *srv_addr = server_address;
  return sock;
}

void con_parse_args(char **argv, con_mode_info *info) {
  memset(info, 0, sizeof(*info));

  if(!strcmp(argv[1], "-l")) {
    info->mode = CON_MODE_SERVER;
    info->address = "0.0.0.0";
  } else {
    info->mode = CON_MODE_CLIENT;
    info->address = argv[1];
  }

  info->port = atoi(argv[2]);
}

/* ******************************************************* */

socket_t con_wait_connection(con_mode_info *info, struct sockaddr_in *cli_addr) {
  socket_t sock;
  socklen_t client_address_len = sizeof(*cli_addr);

  if(info->mode == CON_MODE_SERVER) {
    info->socket = server_init(info->address, info->port);

    if((sock = accept(info->socket, (struct sockaddr *)cli_addr,
                    &client_address_len)) == SOCKET_ERROR)
      fatal("socket accept error [%d]", socket_errno);
  } else {
    info->socket = client_init(info->address, info->port, cli_addr);
    sock = info->socket;
  }

  return sock;
}

/* ******************************************************* */

#ifdef DEBUG_COMMUNICATION
static u_int32_t send_ctr = 0;
static u_int32_t recv_ctr = 0;
#endif

void con_send(socket_t sock, char*data, u_int32_t len) {
  u_int32_t bo_len = htonl(len);

#ifdef DEBUG_COMMUNICATION
  log("[SEND] %u bytes #[%u]", len, send_ctr++);
#endif

  xor_encdec(data, len, ENCODE_KEY);

  // send the size
  send(sock, (char*)&bo_len, sizeof(bo_len), 0);

  // send data
  send(sock, data, len, 0);
}

u_int32_t con_recv(socket_t sock, char*data, u_int32_t len) {
  u_int32_t size;
  u_int32_t sofar;

  // read the size
  sofar = 0;

  while(sofar < sizeof(size)) {
    int n = recv(sock, &((char*)&size)[sofar], sizeof(size) - sofar, 0);

    if(n == SOCKET_ERROR)
      fatal("recv error2: %d", socket_errno);

    if(n == 0)
      fatal("peer disconnected");

    sofar += n;
  }

  size = ntohl(size);

#ifdef DEBUG_COMMUNICATION
    log("[RECV] %u bytes #[%u]", len, recv_ctr++);
#endif

  if(size > len)
    fatal("Packet too big! [%u > %u]", size, len);

  // read the data
  sofar = 0;

  while(sofar < size) {
    int n = recv(sock, &data[sofar], size - sofar, 0);

    if(n == SOCKET_ERROR)
      fatal("recv error3: %d", socket_errno);

    if(n == 0)
      fatal("peer disconnected");

    sofar += n;
  }

  xor_encdec(data, size, ENCODE_KEY);
  return size;
}

/* ******************************************************* */

char* ipv4str(u_int32_t addr, char *buf) {
  struct in_addr sin = {0};
  sin.s_addr = addr;

  inet_ntop(AF_INET, (char*)&sin, buf, INET_ADDRSTRLEN);
  return buf;
}

/* ******************************************************* */

// from netguard
uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, u_int16_t length) {
  register uint32_t sum = start;
  register uint16_t *buf = (uint16_t *) buffer;
  register uint16_t len = length;

  while(len > 1) {
    sum += *buf++;
    len -= 2;
  }

  if(len > 0)
    sum += *((uint8_t *) buf);

  while (sum >> 16)
    sum = (sum & 0xFFFF) + (sum >> 16);

  return (uint16_t) sum;
}

/* ******************************************************* */

// from ntopng
void xor_encdec(char *data, int data_len, char *key) {
  int i, y;

  for(i = 0, y = 0; i < data_len; i++) {
    data[i] ^= key[y++];
    if(key[y] == 0) y = 0;
  }
}

/* ******************************************************* */

int open_tun(const char *tun_dev, const char*ip, const char *netmask) {
  struct ifreq ifr;
  char cmd_buf[255];
  int tun_fd;

  tun_fd = open("/dev/net/tun", O_RDWR);

  if(tun_fd < 0)
    fatal("Cannot open TUN device[%d]: %s", errno, strerror(errno));

  memset(&ifr, 0, sizeof(ifr));
  ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
  strncpy(ifr.ifr_name, tun_dev, IFNAMSIZ);

  int rc = ioctl(tun_fd, TUNSETIFF, (void *)&ifr);

  if(rc < 0)
    fatal("ioctl failed[%d]: %s", rc, strerror(errno));

  // set IPv4 address
  cmd("ip addr add %s/%s dev %s", ip, netmask, tun_dev);

  // bring device upaddr add dev %s %s/ netmask %s mtu %d up
  cmd("ip link set dev %s mtu %d up", tun_dev, TUN_MTU);

  return tun_fd;
}

/* ******************************************************* */

void cmd(const char *fmt, ...) {
  char cmd[256];
  va_list argptr;

  va_start(argptr, fmt);
  int rv = vsnprintf(cmd, sizeof(cmd), fmt, argptr);
  va_end(argptr);

  if((rv < 0) || (rv >= sizeof(cmd)))
    fatal("vsnprintf failed: %d", rv);

  printf("$ %s\n", cmd);
  system(cmd);
}

/* ******************************************************* */

u_int32_t get_default_gw() {
  FILE *fd;
  char *token = NULL;
  u_int32_t gwip = 0;
  char buf[256];

  if(!(fd = fopen("/proc/net/route", "r")))
    return(0);

  // Gateway IP
  while(fgets(buf, sizeof(buf), fd)) {
    if(strtok(buf, "\t") && (token = strtok(NULL, "\t")) && (!strcmp(token, "00000000"))) {
      token = strtok(NULL, "\t");

      if(token) {
        gwip = strtoul(token, NULL, 16);
        break;
      }
    }
  }

  fclose(fd);

  return(gwip);
}

/* ******************************************************* */

int get_default_gw6_and_iface(struct in6_addr *gw, char *iface) {
  FILE *fd;
  char buf[256];
  int rv = 1;

  if(!(fd = fopen("/proc/net/ipv6_route", "r")))
    return(-errno);

  while(fgets(buf, sizeof(buf), fd)) {
    char gw_hex[33];
    char cur_if[IFNAMSIZ + 1];

    if(sscanf(buf, "00000000000000000000000000000000 %*X %*X %*X %32s %*X %*X %*X %*X %s", gw_hex, cur_if) == 2) {
      if(strcmp(cur_if, "lo") != 0) {
        uint32_t *out = (uint32_t*) gw;
        for(int i=0; i<4; i++) {
          int end_idx = (i+1) * 8;
          char tmp = gw_hex[end_idx];
          gw_hex[end_idx] = '\0';
          out[i] = ntohl(strtoul(&gw_hex[i*8], NULL, 16));
          gw_hex[end_idx] = tmp;
        }

        strcpy(iface, cur_if);
        rv = 0;
        break;
      }
    }
  }

  fclose(fd);
  return(rv);
}
