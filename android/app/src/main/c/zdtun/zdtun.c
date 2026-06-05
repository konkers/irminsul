/* ----------------------------------------------------------------------------
 * Zero Dep Tunnel: VPN library without dependencies
 * ----------------------------------------------------------------------------
 *
 * Copyright (C) 2018-22 - Emanuele Faranda
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
#include "socks5.h"
#include "third_party/uthash.h"
#include "third_party/net_headers.h"

#define REPLY_BUF_SIZE 65535
#define DEFAULT_TCP_WINDOW 65535
#define TCP_HEADER_LEN 20
#define IPV4_HEADER_LEN 20
#define IPV6_HEADER_LEN 40
#define UDP_HEADER_LEN 8

#define ICMP_TIMEOUT_SEC 5
#define UDP_TIMEOUT_SEC 30
#define TCP_TIMEOUT_SEC 60

#ifdef WIN32
  // 64 is the per-thread limit on Winsocks
  // use a lower value to leave room for user defined connections
  #define MAX_NUM_SOCKETS 55
  #define NUM_SOCKETS_AFTER_PURGE 40
#else
  // on linux, the maximum open files limit is 1024
  #define MAX_NUM_SOCKETS 128
  #define NUM_SOCKETS_AFTER_PURGE 96
#endif

/* ******************************************************* */

static void destroy_conn(zdtun_t *tun, zdtun_conn_t *conn);

#define default_mss(tun, conn) (tun->mtu - sizeof(struct tcphdr) -\
      ((sock_ipver(tun, conn) == 4) ? sizeof(struct iphdr) : sizeof(struct ipv6_hdr)))

/* ******************************************************* */

typedef struct tcp_data {
  struct tcp_data* next;
  uint16_t len;
  uint16_t sofar;
  uint8_t flags;
  char data[];
} tcp_data_t;

// used to resolve port numbers for IP fragments
typedef struct {
  uint16_t sport;
  uint16_t dport;
} ip_frag_ports_t;

// Keeps track of the client UDP ports numbers (see bind_and_connect_udp)
typedef struct {
  uint32_t key;         // combination of ipver and port number
  uint16_t port;        // the local port associated to the client port
  uint16_t num_uses;    // number of connections using this client port
  UT_hash_handle hh;
} udp_mapping_t;

typedef enum {
  PROXY_NONE = 0,
  PROXY_DNAT,
  PROXY_SOCKS5,
} proxy_mode_t;

typedef struct {
  zdtun_ip_t ip;
  uint16_t port;
  uint8_t ipver;
} proxy_t;


/* ******************************************************* */

typedef struct zdtun_conn {
  zdtun_5tuple_t tuple;
  time_t tstamp;
  socket_t sock;
  zdtun_conn_status_t status;
  int error;

  proxy_t *dnat;
  proxy_mode_t proxy_mode;
  socks5_status_t socks5_status;
  uint8_t socks5_skip;

  union {
    struct {
      tcp_data_t *tx_queue;    // contains TCP segment data to send via the socket
      u_int32_t tx_queue_size; // queued bytes in partial_send
      u_int32_t client_seq;    // next client sequence number
      u_int32_t zdtun_seq;     // next proxy sequence number
      u_int32_t window_size;   // scaled client window size
      u_int16_t mss;           // client MSS
      u_int8_t window_scale;   // client/zdtun TCP window scale

      struct {
        uint8_t fin_ack_sent:1;
        uint8_t client_closed:1;
      };
    } tcp;
  };

  struct {
    u_int8_t pending_queries;
  } dns;

  void *user_data;
  UT_hash_handle hh;  // tuple -> conn
} zdtun_conn_t;

/* ******************************************************* */

typedef struct zdtun_t {
  struct zdtun_callbacks callbacks;
  void *user_data;
  fd_set all_fds;
  fd_set write_fds;
  uint32_t mtu;
  zdtun_statistics_t stats;
  time_t now;
  zdtun_pkt_t last_pkt; // store pkt here to prevent invalid memory access by subsequent API calls
  ip_frag_ports_t id2ports[65536];
  char reply_buf[REPLY_BUF_SIZE];

  proxy_t socks5;

  char *socks5_user;
  char *socks5_pass;

  zdtun_conn_t *conn_table;
  udp_mapping_t *udp_mappings;
} zdtun_t;

/* ******************************************************* */

struct dns_packet {
  uint16_t transaction_id;
  uint16_t flags;
  uint16_t questions;
  uint16_t answ_rrs;
  uint16_t auth_rrs;
  uint16_t additional_rrs;
  uint8_t initial_dot; // just skip
  uint8_t queries[];
} __attribute__((packed));

#define DNS_FLAGS_MASK 0x8000
#define DNS_TYPE_REQUEST 0x0000
#define DNS_TYPE_RESPONSE 0x8000

/* ******************************************************* */

struct ippseudo {
  uint32_t ippseudo_src;    /* source internet address */
  uint32_t ippseudo_dst;    /* destination internet address */
  u_int8_t ippseudo_pad;    /* pad, must be zero */
  u_int8_t ippseudo_p;      /* protocol */
  u_int16_t ippseudo_len;	  /* protocol length */
};

struct ip6_hdr_pseudo {
  struct in6_addr ip6ph_src;
  struct in6_addr ip6ph_dst;
  u_int32_t ip6ph_len;
  u_int8_t ip6ph_zero[3];
  u_int8_t ip6ph_nxt;
} __attribute__((packed));

/* ******************************************************* */

void zdtun_fds(zdtun_t *tun, int *max_fd, fd_set *rdfd, fd_set *wrfd) {
  *max_fd = tun->stats.all_max_fd;
  *rdfd = tun->all_fds;
  *wrfd = tun->write_fds;
}

/* ******************************************************* */

static uint8_t sock_ipver(zdtun_t *tun, zdtun_conn_t *conn) {
  if(conn->proxy_mode == PROXY_DNAT)
    return conn->dnat->ipver;
  else if(conn->proxy_mode == PROXY_SOCKS5)
    return tun->socks5.ipver;
  else
    return conn->tuple.ipver;
}

/* ******************************************************* */

static inline uint32_t udp_mapping_key(const zdtun_5tuple_t *tuple) {
  // ignoring the src IP, assume only 1 client
  return (uint32_t)tuple->ipver << 16 | tuple->src_port;
}

/* ******************************************************* */

static socket_t open_socket(zdtun_t *tun, int domain, int type, int protocol) {
  if(tun->stats.num_open_sockets >= MAX_NUM_SOCKETS)
    return(INVALID_SOCKET);

  socket_t sock = socket(domain, type, protocol);

  if(sock == INVALID_SOCKET)
    return(INVALID_SOCKET);

#ifndef WIN32
  if(sock < 0)
    return(INVALID_SOCKET);

  /* FD_SETSIZE should never be execeeded, otherwise FD_SET will crash */
  if(sock >= FD_SETSIZE) {
    error("socket exceeds FD_SETSIZE");
    closesocket(sock);

    return(INVALID_SOCKET);
  }
#endif

  if(tun->callbacks.on_socket_open)
    tun->callbacks.on_socket_open(tun, sock);

  FD_SET(sock, &tun->all_fds);
  tun->stats.num_open_sockets++;

#ifndef WIN32
  tun->stats.all_max_fd = max(tun->stats.all_max_fd, sock);
#endif

  switch(protocol) {
    case IPPROTO_UDP:
      tun->stats.num_udp_opened++;
      break;
    case IPPROTO_TCP:
      tun->stats.num_tcp_opened++;
      break;
    case IPPROTO_ICMP:
    case IPPROTO_ICMPV6:
      tun->stats.num_icmp_opened++;
      break;
  }

  return(sock);
}

/* ******************************************************* */

static void close_socket(zdtun_t *tun, socket_t sock) {
  if(sock == INVALID_SOCKET)
    return;

  int rv = closesocket(sock);

  if(rv == SOCKET_ERROR) {
    error("closesocket failed[%d]", socket_errno);
  } else if(tun->callbacks.on_socket_close)
    tun->callbacks.on_socket_close(tun, sock);

  FD_CLR(sock, &tun->all_fds);
  FD_CLR(sock, &tun->write_fds);

  tun->stats.num_open_sockets = max(tun->stats.num_open_sockets-1, 0);
}

/* ******************************************************* */

// Returns != 0 if the error is related to a client side problem
static int close_with_socket_error(zdtun_t *tun, zdtun_conn_t *conn, const char *ctx) {
  int rv = 0;
  zdtun_conn_status_t status;
  char buf[256];

  switch(socket_errno) {
    case socket_con_reset:
      status = CONN_STATUS_RESET;
      break;
    case socket_broken_pipe:
      status = CONN_STATUS_SOCKET_ERROR;
      break;
    case socket_con_refused:
      status = CONN_STATUS_SOCKET_ERROR;
      break;
    case socket_con_aborted:
      status = CONN_STATUS_SOCKET_ERROR;
      break;
    case socket_net_unreachable:
    case socket_host_unreachable:
      status = CONN_STATUS_UNREACHABLE;
      rv = -1;
      break;
    default:
      status = CONN_STATUS_SOCKET_ERROR;
      rv = -1;
      break;
  }

  zdtun_5tuple2str(&conn->tuple, buf, sizeof(buf));

  if(rv == 0) {
    log("%s error[%d]: %s - %s", ctx, socket_errno, strerror(socket_errno), buf);
  } else {
    error("%s error[%d]: %s - %s", ctx, socket_errno, strerror(socket_errno), buf);
  }

  conn->error = socket_errno;
  zdtun_conn_close(tun, conn, status);
  return(rv);
}

/* ******************************************************* */

void* zdtun_userdata(zdtun_t *tun) {
  return(tun->user_data);
}

/* ******************************************************* */

static time_t zdtun_now(zdtun_t *tun) {
  struct timespec ts;

  if(!clock_gettime(CLOCK_MONOTONIC_COARSE, &ts))
    tun->now = ts.tv_sec;
  else
    error("clock_gettime failed");

  return tun->now;
}

/* ******************************************************* */

void zdtun_set_socks5_proxy(zdtun_t *tun, const zdtun_ip_t *proxy_ip,
        uint16_t proxy_port, uint8_t ipver) {
  tun->socks5.ip = *proxy_ip;
  tun->socks5.port = proxy_port;
  tun->socks5.ipver = ipver;
}

/* ******************************************************* */

void zdtun_set_socks5_userpass(zdtun_t *tun, const char *username, const char *password) {
  free(tun->socks5_user);
  free(tun->socks5_pass);

  tun->socks5_user = strdup(username);
  tun->socks5_pass = strdup(password);
}

/* ******************************************************* */

/* Connection methods */
void* zdtun_conn_get_userdata(const zdtun_conn_t *conn) {
  return conn->user_data;
}

void zdtun_conn_set_userdata(zdtun_conn_t *conn, void *userdata) {
  conn->user_data = userdata;
}

const zdtun_5tuple_t* zdtun_conn_get_5tuple(const zdtun_conn_t *conn) {
  return &conn->tuple;
}

time_t zdtun_conn_get_last_seen(const zdtun_conn_t *conn) {
  return conn->tstamp;
}

zdtun_conn_status_t zdtun_conn_get_status(const zdtun_conn_t *conn) {
  return conn->status;
}
int zdtun_conn_get_error(const zdtun_conn_t *conn) {
  return conn->error;
}


socket_t zdtun_conn_get_socket(const zdtun_conn_t *conn) {
  return conn->sock;
}

void zdtun_conn_proxy(zdtun_conn_t *conn) {
  // NOTE: only TCP is currently supported
  if(conn->tuple.ipproto == IPPROTO_TCP)
    conn->proxy_mode = PROXY_SOCKS5;
}

void zdtun_conn_dnat(zdtun_conn_t *conn, const zdtun_ip_t *proxy_ip, uint16_t proxy_port, uint8_t ipver) {
  proxy_t *proxy;
  safe_alloc(proxy, proxy_t);

  proxy->ip = *proxy_ip;
  proxy->port = proxy_port;
  proxy->ipver = ipver;

  if(conn->dnat)
    free(conn->dnat);

  conn->dnat = proxy;
  conn->proxy_mode = PROXY_DNAT;
}

/* ******************************************************* */

zdtun_t* zdtun_init(struct zdtun_callbacks *callbacks, void *udata) {
  zdtun_t *tun;
  safe_alloc(tun, zdtun_t);

  if(!tun) {
    error("zdtun_t calloc error");
    return NULL;
  }

  /* Verify mandatory callbacks */
  if(!callbacks) {
    error("callbacks parameter is NULL");
    return NULL;
  }
  if(!callbacks->send_client) {
    error("missing mandatory send_client callback");
    return NULL;
  }

  tun->user_data = udata;
  tun->mtu = 1500;
  memcpy(&tun->callbacks, callbacks, sizeof(tun->callbacks));

  FD_ZERO(&tun->all_fds);
  FD_ZERO(&tun->write_fds);

  return tun;
}

/* ******************************************************* */

void zdtun_finalize(zdtun_t *tun) {
  zdtun_conn_t *conn, *tmp;

  HASH_ITER(hh, tun->conn_table, conn, tmp) {
    destroy_conn(tun, conn);
  }

  // tun->udp_mappings is cleaned up during destroy_conn

  free(tun->socks5_user);
  free(tun->socks5_pass);
  free(tun);
}

/* ******************************************************* */

static int send_to_client(zdtun_t *tun, zdtun_conn_t *conn, int l3_len) {
  int size = l3_len + zdtun_iphdr_len(tun, conn);

  if(zdtun_parse_pkt(tun, tun->reply_buf, size, &tun->last_pkt) < 0) {
    error("zdtun_parse_pkt failed, this should never happen");
    return -1;
  }

  int rv = tun->callbacks.send_client(tun, &tun->last_pkt, conn);

  if(rv == 0) {
    if(tun->callbacks.account_packet)
        tun->callbacks.account_packet(tun, &tun->last_pkt, 0 /* from zdtun */, conn);
  } else {
    debug("send_client failed [%d]", rv);

    if(conn->tuple.ipproto == IPPROTO_TCP)
        // important: set this to prevent close_conn to call send_to_client again in a loop
        conn->tcp.fin_ack_sent = 1;

    zdtun_conn_close(tun, conn, CONN_STATUS_CLIENT_ERROR);
  }

  return(rv);
}

/* ******************************************************* */

#ifndef WIN32

// Try to get the free space in the socket TX buffer. The value returned
// is just an approximation which helps tuning the TCP receiver window
// seen by the client, thus possibly throttling the upload before
// reaching the bottleneck and subsequent retransmissions.
//
// http://lkml.iu.edu/hypermail/linux/kernel/0502.2/1087.html
// https://gitlab.torproject.org/tpo/core/tor/-/issues/12890
static int get_available_sndbuf(zdtun_conn_t *conn) {
  int bufsize = 0;
  int queued = 0;
  socklen_t len = sizeof(bufsize);

  // Get the available bytes in the send buffer
  getsockopt(conn->sock, SOL_SOCKET, SO_SNDBUF, &bufsize, &len);
  if(bufsize == 0)
    bufsize = DEFAULT_TCP_WINDOW;

  ioctl(conn->sock, SIOCOUTQ, &queued);
  int sockbuf_avail = bufsize - queued - conn->tcp.tx_queue_size;

  return max(sockbuf_avail, 0);
}

#endif

/* ******************************************************* */

int zdtun_iphdr_len(zdtun_t *tun, zdtun_conn_t *conn) {
  return (sock_ipver(tun, conn) == 4) ? IPV4_HEADER_LEN : IPV6_HEADER_LEN;
}

/* ******************************************************* */

void zdtun_make_iphdr(zdtun_t *tun, zdtun_conn_t *conn, char *pkt_buf, u_int16_t l3_len) {
  if(sock_ipver(tun, conn) == 4) {
    struct iphdr *ip = (struct iphdr*)pkt_buf;
    uint16_t tot_len = l3_len + IPV4_HEADER_LEN;

    memset(ip, 0, IPV4_HEADER_LEN);
    ip->ihl = 5; // 5 * 4 = 20 = IPV4_HEADER_LEN
    ip->version = 4;
    ip->frag_off = htons(0x4000); // don't fragment
    ip->tot_len = htons(tot_len);
    ip->ttl = 64; // hops
    ip->protocol = conn->tuple.ipproto;
    ip->saddr = conn->tuple.dst_ip.ip4;
    ip->daddr = conn->tuple.src_ip.ip4;

    ip->check = ~calc_checksum(0, (u_int8_t*)ip, IPV4_HEADER_LEN);
  } else {
    struct ipv6_hdr *ip = (struct ipv6_hdr*)pkt_buf;

    memset(ip, 0, IPV6_HEADER_LEN);
    ip->version = 6;
    ip->payload_len = htons(l3_len);
    ip->nexthdr = (conn->tuple.ipproto != IPPROTO_ICMP) ? conn->tuple.ipproto : IPPROTO_ICMPV6;
    ip->hop_limit = 64;
    ip->saddr = conn->tuple.dst_ip.ip6;
    ip->daddr = conn->tuple.src_ip.ip6;
  }
}

/* ******************************************************* */

uint16_t zdtun_l3_checksum(zdtun_t *tun, zdtun_conn_t *conn, char *ipbuf, char *l3, uint16_t l3_len) {
  uint8_t ipver = sock_ipver(tun, conn);
  uint8_t ipproto = conn->tuple.ipproto;
  uint16_t rv = calc_checksum(0, (uint8_t*)l3, l3_len);

  if(ipver == 4) {
    struct iphdr *ip_header = (struct iphdr*)ipbuf;
    struct ippseudo pseudo = {0};

    pseudo.ippseudo_src = ip_header->saddr;
    pseudo.ippseudo_dst = ip_header->daddr;
    pseudo.ippseudo_p = ipproto;
    pseudo.ippseudo_len = htons(l3_len);

    rv = calc_checksum(rv, (uint8_t*)&pseudo, sizeof(pseudo));
  } else {
    struct ipv6_hdr *ip_header = (struct ipv6_hdr*)ipbuf;
    struct ip6_hdr_pseudo pseudo;
    memset(&pseudo, 0, sizeof(pseudo));

    pseudo.ip6ph_src = ip_header->saddr;
    pseudo.ip6ph_dst = ip_header->daddr;
    pseudo.ip6ph_len = ip_header->payload_len;
    pseudo.ip6ph_nxt = ((ipver == 6) && (ipproto == IPPROTO_ICMP)) ? IPPROTO_ICMPV6 : ipproto;

    rv = calc_checksum(rv, (uint8_t*)&pseudo, sizeof(pseudo));
  }

  return ~rv;
}

/* ******************************************************* */

static void build_reply_tcpip(zdtun_t *tun, zdtun_conn_t *conn, u_int8_t flags,
        u_int16_t l4_len, u_int16_t optsoff) {
  uint8_t ipver = sock_ipver(tun, conn);
  int iphdr_len = zdtun_iphdr_len(tun, conn);
  const u_int16_t l3_len = l4_len + TCP_HEADER_LEN + (optsoff * 4);
  struct tcphdr *tcp = (struct tcphdr *)&tun->reply_buf[iphdr_len];
  uint32_t max_win = ((uint32_t)0xFFFF) << conn->tcp.window_scale;
  uint32_t tcpwin;

  memset(tcp, 0, TCP_HEADER_LEN);
  tcp->th_sport = conn->tuple.dst_port;
  tcp->th_dport = conn->tuple.src_port;
  tcp->th_seq = htonl(conn->tcp.zdtun_seq);
  tcp->th_ack = (flags & TH_ACK) ? htonl(conn->tcp.client_seq) : 0;
  tcp->th_off = 5 + optsoff;
  tcp->th_flags = flags;

#ifdef WIN32
  tcpwin = max_win;
#else
  // To avoid slowdowns, it's better to check the free space in the send
  // buffer and reduce the TCP window accordingly. If a 0 window is sent,
  // the client will periodically send TCP_KEEPALIVE to wake the connection.
  // This prevents connection stall.
  tcpwin = min(get_available_sndbuf(conn), max_win);
#endif

  tcp->th_win = htons(tcpwin >> conn->tcp.window_scale);

  zdtun_make_iphdr(tun, conn, tun->reply_buf, l3_len);
  tcp->th_sum = zdtun_l3_checksum(tun, conn, tun->reply_buf, (char*)tcp, l3_len);
}

/* ******************************************************* */

// It is used to defer the destroy_conn function to let the user
// consume the connection without accessing invalid memory. The connections
// will be (later) destroyed by zdtun_purge_expired.
// May be called multiple times.
void zdtun_conn_close(zdtun_t *tun, zdtun_conn_t *conn, zdtun_conn_status_t status) {
  if(conn->status >= CONN_STATUS_CLOSED)
    return;

  if(conn->tuple.ipproto == IPPROTO_UDP) {
    udp_mapping_t *mapping;
    uint32_t key = udp_mapping_key(&conn->tuple);

    HASH_FIND(hh, tun->udp_mappings, &key, sizeof(key), mapping);

    if(mapping && (--mapping->num_uses == 0)) {
      HASH_DELETE(hh, tun->udp_mappings, mapping);
      free(mapping);
    }
  }

  close_socket(tun, conn->sock);
  conn->sock = INVALID_SOCKET;

  if((conn->tuple.ipproto == IPPROTO_TCP)
      && !conn->tcp.fin_ack_sent) {
    // Send TCP RST
    build_reply_tcpip(tun, conn, TH_RST | TH_ACK, 0, 0);
    send_to_client(tun, conn, TCP_HEADER_LEN);
  }

  if(conn->tuple.ipproto == IPPROTO_TCP) {
    tcp_data_t *cur = conn->tcp.tx_queue;

    // free tx_queue
    while(cur) {
      tcp_data_t *next = cur->next;
      free(cur);
      cur = next;
    }

    conn->tcp.tx_queue = NULL;
  }

  conn->status = (status >= CONN_STATUS_CLOSED) ? status : CONN_STATUS_CLOSED;

  if(tun->callbacks.on_connection_close)
    tun->callbacks.on_connection_close(tun, conn);
}

/* ******************************************************* */

// Avoid calling destroy_conn inside zdtun_forward_full as it may
// generate dangling pointers. Use close_conn instead.
static void destroy_conn(zdtun_t *tun, zdtun_conn_t *conn) {
  debug("PURGE SOCKET (type=%d)", conn->tuple.ipproto);

  zdtun_conn_close(tun, conn, CONN_STATUS_CLOSED);

  if(conn->dnat)
    free(conn->dnat);

  switch(conn->tuple.ipproto) {
    case IPPROTO_TCP:
      tun->stats.num_tcp_conn--;
      break;
    case IPPROTO_UDP:
      tun->stats.num_udp_conn--;
      break;
    case IPPROTO_ICMP:
      tun->stats.num_icmp_conn--;
      break;
  }

  HASH_DELETE(hh, tun->conn_table, conn);
  free(conn);
}

/* ******************************************************* */

static int send_syn_ack(zdtun_t *tun, zdtun_conn_t *conn) {
  int rv;
  int iphdr_len = zdtun_iphdr_len(tun, conn);
  uint8_t *opts = (uint8_t*) &tun->reply_buf[iphdr_len + TCP_HEADER_LEN];

  // MSS option
  *(opts++) = 2;
  *(opts++) = 4;
  *((uint16_t*)opts) = htons(default_mss(tun, conn));
  opts += 2;

  // Window Scale
  *(opts++) = 3;
  *(opts++) = 3;
  *(opts++) = conn->tcp.window_scale;

  // End, aligned to 32 bits
  *(opts++) = 0;

  build_reply_tcpip(tun, conn, TH_SYN | TH_ACK, 0, 2 /* n. 32bit words*/);

  if((rv = send_to_client(tun, conn, TCP_HEADER_LEN + 8 /* opts length */)) == 0)
    conn->tcp.zdtun_seq += 1;

  return rv;
}

/* ******************************************************* */

static int tcp_socket_syn(zdtun_t *tun, zdtun_conn_t *conn) {
  // disable non-blocking mode from now on

#ifdef WIN32
  unsigned nonblocking = 0;
  ioctlsocket(conn->sock, FIONBIO, &nonblocking);
#else
  int flags = fcntl(conn->sock, F_GETFL);

  if(fcntl(conn->sock, F_SETFL, flags &(~O_NONBLOCK)) == -1)
    error("Cannot disable non-blocking: %d", errno);
#endif

  FD_CLR(conn->sock, &tun->write_fds);
  conn->status = CONN_STATUS_CONNECTED;

  if(conn->proxy_mode == PROXY_SOCKS5) {
    // wait before sending the SYN+ACK
    return socks5_connect(tun, conn);
  }

  return send_syn_ack(tun, conn);
}

/* ******************************************************* */

static void tcp_socket_fin_ack(zdtun_t *tun, zdtun_conn_t *conn) {
  build_reply_tcpip(tun, conn, TH_FIN | TH_ACK, 0, 0);

  if(send_to_client(tun, conn, TCP_HEADER_LEN) == 0)
    conn->tcp.zdtun_seq += 1;
}

/* ******************************************************* */

zdtun_conn_t* zdtun_lookup(zdtun_t *tun, const zdtun_5tuple_t *tuple, uint8_t create) {
  zdtun_conn_t *conn = NULL;

  HASH_FIND(hh, tun->conn_table, tuple, sizeof(*tuple), conn);
  if(conn && (conn->status >= CONN_STATUS_CLOSED)) {
    // avoid returning connections to purge, for which the close_callback was already called and
    // user data was probably already deallocated.
    destroy_conn(tun, conn);
    conn = NULL;
  }

  if(!conn && create) {
    if(tun->stats.num_open_sockets >= MAX_NUM_SOCKETS) {
      debug("Force purge!");
      zdtun_purge_expired(tun);
    }

    /* Add a new connection */
    safe_alloc(conn, zdtun_conn_t);
    conn->sock = INVALID_SOCKET;
    conn->tuple = *tuple;
    conn->tstamp = zdtun_now(tun);

    if(tun->callbacks.on_connection_open) {
      if(tun->callbacks.on_connection_open(tun, conn) != 0) {
        debug("Dropping connection");
        free(conn);
        return NULL;
      }
    }

    HASH_ADD(hh, tun->conn_table, tuple, sizeof(*tuple), conn);

    switch(conn->tuple.ipproto) {
      case IPPROTO_TCP:
        tun->stats.num_tcp_conn++;
        break;
      case IPPROTO_UDP:
        tun->stats.num_udp_conn++;
        break;
      case IPPROTO_ICMP:
        tun->stats.num_icmp_conn++;
        break;
    }
  }

  return conn;
}

/* ******************************************************* */

static void check_dns_request(zdtun_conn_t *conn, char *l4_payload, uint16_t l4_len) {
  struct dns_packet *dns;

  if((l4_len < sizeof(struct dns_packet)) || (conn->tuple.dst_port != ntohs(53)))
    return;

  dns = (struct dns_packet*)l4_payload;

  if((dns->flags & DNS_FLAGS_MASK) == DNS_TYPE_REQUEST)
    conn->dns.pending_queries++;
}

/* ******************************************************* */

static int check_dns_purge(zdtun_t *tun, zdtun_conn_t *conn,
        char *l4_payload, uint16_t l4_len) {
    struct dns_packet *dns;

    if((l4_len < sizeof(struct dns_packet)) || (conn->tuple.dst_port != ntohs(53)))
      return(1);

    dns = (struct dns_packet*)l4_payload;

    if(((dns->flags & DNS_FLAGS_MASK) == DNS_TYPE_RESPONSE)
        && (conn->dns.pending_queries > 0)) {
      conn->dns.pending_queries--;

      if(conn->dns.pending_queries == 0) {
        char buf[256];

        /* DNS responses received, can now purge the conn */
        debug("DNS purge: %s", zdtun_5tuple2str(&conn->tuple, buf, sizeof(buf)));
        zdtun_conn_close(tun, conn, CONN_STATUS_CLOSED);

        /* purged */
        return(0);
      }
    }

    return(1);
}

/* ******************************************************* */

static int is_upper_layer(int proto) {
  return (proto == IPPROTO_TCP ||
          proto == IPPROTO_UDP ||
          proto == IPPROTO_ICMP ||
          proto == IPPROTO_ICMPV6);
}

/* ******************************************************* */

int zdtun_parse_pkt(zdtun_t *tun, const char *_pkt_buf, uint16_t pkt_len, zdtun_pkt_t *pkt) {
  memset(pkt, 0, sizeof(zdtun_pkt_t));

  if(pkt_len < IPV4_HEADER_LEN) {
    debug("Ignoring non IP packet (len: %d)", pkt_len);
    return -1;
  }

  char *pkt_buf = (char *)_pkt_buf; /* needed to set the zdtun_pkt_t pointers */
  uint8_t ipver = (*pkt_buf) >> 4;
  uint8_t ipproto;
  int iphdr_len;

  if((ipver != 4) && (ipver != 6)) {
    debug("Ignoring non IP packet (len: %d, v: %d)", pkt_len, ipver);
    return -1;
  }

  if(ipver == 4) {
    struct iphdr *ip_header = (struct iphdr*) pkt_buf;
    iphdr_len = ip_header->ihl * 4;

    if(pkt_len < iphdr_len) {
      debug("IPv4 packet too short: %d bytes", pkt_len);
      return -1;
    }

    uint16_t tot_len = ntohs(ip_header->tot_len);
    if(tot_len < iphdr_len) {
      debug("Invalid IPv4 packet: tot_len=%d, hdr_len=%d", tot_len, iphdr_len);
      return -1;
    }

    // exclude non-IP data
    pkt_len = min(pkt_len, tot_len);

    pkt->tuple.src_ip.ip4 = ip_header->saddr;
    pkt->tuple.dst_ip.ip4 = ip_header->daddr;
    ipproto = ip_header->protocol;

    if (ip_header->frag_off & htons(0x1FFF)) {
      // this an IP fragment (not the first one)
      pkt->flags |= ZDTUN_PKT_IS_FRAGMENT;
    } else if (ip_header->frag_off & htons(0x2000)) { // IP_MF
      // this the first IP fragment
      pkt->flags |= ZDTUN_PKT_IS_FRAGMENT;
      pkt->flags |= ZDTUN_PKT_IS_FIRST_FRAGMENT;
    }
  } else {
    struct ipv6_hdr *ip_header = (struct ipv6_hdr*) pkt_buf;

    if(pkt_len < sizeof(struct ipv6_hdr)) {
      debug("IPv6 packet too short: %d bytes", pkt_len);
      return -1;
    }

    iphdr_len = sizeof(struct ipv6_hdr);

    if(!is_upper_layer(ip_header->nexthdr)) {
      debug("IPv6 extensions not supported: %d", ip_header->nexthdr);
      return -1;
    }

    // exclude non-IP data
    uint16_t payload_len = ntohs(ip_header->payload_len);
    pkt_len = min(pkt_len, payload_len + iphdr_len);

    pkt->tuple.src_ip.ip6 = ip_header->saddr;
    pkt->tuple.dst_ip.ip6 = ip_header->daddr;

    // Treat IPPROTO_ICMPV6 as IPPROTO_ICMP for simplicity
    ipproto = (ip_header->nexthdr != IPPROTO_ICMPV6) ? ip_header->nexthdr : IPPROTO_ICMP;
  }

  pkt->buf = pkt_buf;
  pkt->l3 = pkt_buf;
  pkt->tuple.ipproto = ipproto;
  pkt->tuple.ipver = ipver;
  pkt->len = pkt_len;
  pkt->ip_hdr_len = iphdr_len;
  pkt->l4 = &pkt_buf[iphdr_len];

  if((pkt->flags & ZDTUN_PKT_IS_FRAGMENT) &&
     !(pkt->flags & ZDTUN_PKT_IS_FIRST_FRAGMENT)) {
    // this an IP fragment (not the first one)
    ip_frag_ports_t *ports = &tun->id2ports[pkt->ip4->id];

    // may be 0
    pkt->tuple.src_port = ports->sport;
    pkt->tuple.dst_port = ports->dport;
    pkt->l4_hdr_len = 0;

    if(!(pkt->ip4->frag_off & htons(0x2000))) { // !IP_MF
      // this is the last fragment. Reset the ports to avoid matching unrelated fragments.
      // This assumes that the previous fragments are not lost and retransmitted afterwards.
      ports->sport = 0;
      ports->dport = 0;
    }
  } else if(ipproto == IPPROTO_TCP) {
    struct tcphdr *data = pkt->tcp;
    int32_t tcp_header_len;

    if(pkt_len < (iphdr_len + TCP_HEADER_LEN)) {
      debug("Packet too small for TCP[%d]", pkt_len);
      return -1;
    }

    tcp_header_len = data->th_off * 4;

    if(pkt_len < (iphdr_len + tcp_header_len)) {
      debug("Malformed TCP packet");
      return -1;
    }

    pkt->l4_hdr_len = tcp_header_len;
    pkt->tuple.src_port = data->th_sport;
    pkt->tuple.dst_port = data->th_dport;
  } else if(ipproto == IPPROTO_UDP) {
    struct udphdr *data = pkt->udp;

    if(pkt_len < (iphdr_len + UDP_HEADER_LEN)) {
      debug("Packet too small for UDP[%d]", pkt_len);
      return -1;
    }

    pkt->l4_hdr_len = 8;
    pkt->tuple.src_port = data->uh_sport;
    pkt->tuple.dst_port = data->uh_dport;
  } else if(ipproto == IPPROTO_ICMP) {
    struct icmphdr *data = (struct icmphdr*) &pkt_buf[iphdr_len];

    if(pkt_len < (iphdr_len + sizeof(struct icmphdr))) {
      debug("Packet too small for ICMP");
      return -1;
    }

    if((data->type != ICMP_ECHO) && (data->type != ICMP_ECHOREPLY) &&
        (data->type != ICMPv6_ECHO) && (data->type != ICMPv6_ECHOREPLY)) {
      debug("Discarding unsupported ICMP[%d]", data->type);
      return -2;
    }

    pkt->l4_hdr_len = sizeof(struct icmphdr);

    // NOTE: echo ID in the source port is the same convention used by linux for ICMP connections
    if((data->type == ICMP_ECHO) || (data->type == ICMPv6_ECHO)) {
      pkt->tuple.echo_id = data->un.echo.id;
      pkt->tuple.dst_port = 0;
    } else {
      pkt->tuple.echo_id = 0;
      pkt->tuple.dst_port = data->un.echo.id;
    }
  } else {
    debug("Packet with unknown protocol: %u", ipproto);
    return -3;
  }

  if(pkt->flags & ZDTUN_PKT_IS_FIRST_FRAGMENT) {
    // save the ports to restore them on the next fragments
    // Assumption: the IP ID field of different connections does not collide
    ip_frag_ports_t *ports = &tun->id2ports[pkt->ip4->id];
    ports->sport = pkt->tuple.src_port;
    ports->dport = pkt->tuple.dst_port;
  }

  pkt->l7_len = pkt_len - iphdr_len - pkt->l4_hdr_len;
  pkt->l7 = &pkt_buf[iphdr_len + pkt->l4_hdr_len];
  return 0;
}

/* ******************************************************* */

void zdtun_set_mtu(zdtun_t *tun, int mtu) {
  tun->mtu = min(mtu, REPLY_BUF_SIZE);
}

/* ******************************************************* */

int zdtun_parse_ip(const char *ip_str, zdtun_ip_t *parsed) {
  int rv = -1;

  if(strchr(ip_str, '.')) {
    if(inet_pton(AF_INET, ip_str, &parsed->ip4) > 0)
      rv = 4;
  } else{
    if(inet_pton(AF_INET6, ip_str, &parsed->ip6) > 0)
      rv = 6;
  }

  return rv;
}

/* ******************************************************* */

int zdtun_cmp_ip(int ipver, zdtun_ip_t *ip_a, zdtun_ip_t *ip_b) {
  if(ipver == 4)
    return(memcmp(&ip_a->ip4, &ip_b->ip4, 4));
  else
    return(memcmp(&ip_a->ip6, &ip_b->ip6, 16));
}

/* ******************************************************* */

static void fill_conn_sockaddr(zdtun_t *tun, zdtun_conn_t *conn,
        struct sockaddr_in6 *addr6, socklen_t *addrlen) {
  uint8_t ipver = sock_ipver(tun, conn);
  const proxy_t *proxy;

  if(conn->proxy_mode == PROXY_DNAT)
    proxy = conn->dnat;
  else if(conn->proxy_mode == PROXY_SOCKS5)
    proxy = &tun->socks5;
  else
    proxy = NULL;

  if(ipver == 4) {
    // struct sockaddr_in is smaller than struct sockaddr_in6
    struct sockaddr_in *addr4 = (struct sockaddr_in*)addr6;

    addr4->sin_family = AF_INET;
    addr4->sin_addr.s_addr = proxy ? proxy->ip.ip4 : conn->tuple.dst_ip.ip4;
    addr4->sin_port = proxy ? proxy->port : conn->tuple.dst_port;
    *addrlen = sizeof(struct sockaddr_in);
  } else {
    addr6->sin6_family = AF_INET6;
    addr6->sin6_addr = proxy ? proxy->ip.ip6 : conn->tuple.dst_ip.ip6;
    addr6->sin6_port = proxy ? proxy->port : conn->tuple.dst_port;
    *addrlen = sizeof(struct sockaddr_in6);
  }
}

/* ******************************************************* */

static int enqueue_tcp_data(zdtun_t *tun, zdtun_conn_t *conn, const char *buf, int bufsize, uint8_t flags) {
  tcp_data_t *item;

  item = calloc(1, sizeof(tcp_data_t) + bufsize);
  if(!item) {
    error("calloc(tcp_data_t) failed");
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    return -1;
  }

  item->flags = flags;
  item->len = bufsize;
  memcpy(item->data, buf, bufsize);

  // append
  if(conn->tcp.tx_queue) {
    tcp_data_t *cur = conn->tcp.tx_queue;
    while(cur->next)
      cur = cur->next;
    cur->next = item;
  } else
    conn->tcp.tx_queue = item;

  conn->tcp.tx_queue_size += bufsize;

  // will be handled in handle_queued_tcp_data
  // increment of client_seq and sending of ACK is also deferred
  FD_SET(conn->sock, &tun->write_fds);

  return 0;
}

/* ******************************************************* */

// returns 0 on success
// returns <0 on error
// no_ack: can be used to avoid sending the ACK to the client and keep
// its sequence number unchanged. This is needed to implement out of band
// data.
static int handle_tcp_fwd(zdtun_t *tun, const zdtun_pkt_t *pkt,
          zdtun_conn_t *conn) {
  struct tcphdr *data = pkt->tcp;
  int family = (sock_ipver(tun, conn) == 4) ? PF_INET : PF_INET6;
  int val;

  if(data->th_flags & TH_URG) {
    error("URG data not supported");
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    return -1;
  }

  if(conn->status == CONN_STATUS_CONNECTING) {
    debug("ignore TCP packet, we are connecting");
    return 0;
  } else if(conn->status == CONN_STATUS_NEW) {
    debug("Allocating new TCP socket for port %d", ntohs(conn->tuple.dst_port));
    socket_t tcp_sock = open_socket(tun, family, SOCK_STREAM, IPPROTO_TCP);

    if(tcp_sock == INVALID_SOCKET) {
      error("Cannot create TCP socket[%d]", socket_errno);
      conn->status = CONN_STATUS_SOCKET_ERROR;
      return -1;
    }

    conn->sock = tcp_sock;

    // Disable Nagle algorithm. We will manually buffer data with MSG_MORE
    // when needed.
    val = 1;
    if(setsockopt(tcp_sock, SOL_TCP, TCP_NODELAY, &val, sizeof(val)) < 0)
      error("setsockopt TCP_NODELAY failed");

    // Setup for the connection
    struct sockaddr_in6 servaddr = {0};
    socklen_t addrlen;
    fill_conn_sockaddr(tun, conn, &servaddr, &addrlen);

#ifdef WIN32
    unsigned nonblocking = 1;
    ioctlsocket(tcp_sock, FIONBIO, &nonblocking);
#else
    int flags = fcntl(tcp_sock, F_GETFL);

    if(fcntl(tcp_sock, F_SETFL, flags | O_NONBLOCK) == -1)
      error("Cannot set socket non blocking: %d", errno);
#endif

    uint8_t in_progress = 0;

    // Account the SYN
    if(tun->callbacks.account_packet)
      tun->callbacks.account_packet(tun, pkt, 1 /* to zdtun */, conn);

    // TCP options
    uint8_t optslen = data->th_off * 4 - TCP_HEADER_LEN;
    uint8_t *opts = (uint8_t*)data + TCP_HEADER_LEN;
    uint16_t mss = default_mss(tun, conn);
    uint8_t scale = 0;

    while(optslen > 1) {
      uint8_t kind = *opts++;
      uint8_t len;

      if(kind == 1) { // NOP
        optslen--;
        continue;
      }

      len = *opts++;

      if((kind == 0) || (len < 2) || (optslen < len))
        break;

      if((kind == 2) && (len == 4)) // MSS
        mss = ntohs(*(uint16_t*)opts);

      if((kind == 3) && (len == 3)) // Window Scale
        scale = *opts;

      opts += (len - 2);
      optslen -= len;
    }

    debug("MSS: %d, scale: %d\n", mss, scale);

    conn->tcp.window_size = ntohs(data->th_win) << scale;
    conn->tcp.window_scale = scale;
    conn->tcp.mss = mss;

    // connect with the server
    if(connect(tcp_sock, (struct sockaddr *) &servaddr, addrlen) == SOCKET_ERROR) {
      if(socket_errno == socket_in_progress) {
        debug("Connection in progress");
        in_progress = 1;
      } else {
        close_with_socket_error(tun, conn, "TCP connect");
        return 0;
      }
    }

    conn->tcp.client_seq = ntohl(data->th_seq) + 1;
    conn->tcp.zdtun_seq = 0x77EB77EB;

    if(!in_progress)
      return tcp_socket_syn(tun, conn);

    conn->status = CONN_STATUS_CONNECTING;
    FD_SET(tcp_sock, &tun->write_fds);
    return 0;
  }

  // Here a connection is already active
  if(tun->callbacks.account_packet)
     tun->callbacks.account_packet(tun, pkt, 1 /* to zdtun */, conn);

  uint32_t seq = ntohl(data->th_seq);
  uint8_t is_keep_alive = ((data->th_flags & TH_ACK) &&
    ((seq + 1) == conn->tcp.client_seq));

  if(!is_keep_alive && (seq != (conn->tcp.client_seq + conn->tcp.tx_queue_size))) {
    debug("ignoring out of sequence data: expected %d, got %d", conn->tcp.client_seq, seq);
    return 0;
  }

  if(socks5_in_progress(conn)) {
    error("Got data while SOCKS5 in progress (status: %d, %d bytes, TCP flags: %d)",
        conn->socks5_status, pkt->l7_len, data->th_flags);

    zdtun_conn_close(tun, conn, CONN_STATUS_SOCKS5_ERROR);
    return -1;
  }

  if(data->th_flags & TH_RST) {
    debug("Got TCP reset from client");
    zdtun_conn_close(tun, conn, CONN_STATUS_CLOSED);

    return 0;
  } else if((data->th_flags & (TH_FIN | TH_ACK)) == (TH_FIN | TH_ACK)) {
    int rv;

    debug("Got TCP FIN+ACK from client");

    if(conn->sock != INVALID_SOCKET) {
      // Half close the socket to possibly signal the remote server
      // (e.g. in after an HTTP request is completed).

      if(shutdown(conn->sock, SHUT_WR) != 0)
        debug("shutdown failed[%d] %s", errno, strerror(errno));
    }

    conn->tcp.client_seq += ((uint32_t)pkt->l7_len) + 1;
    conn->tcp.client_closed = 1;

    // send the ACK
    build_reply_tcpip(tun, conn, TH_ACK, 0, 0);
    rv = send_to_client(tun, conn, TCP_HEADER_LEN);

    if(conn->sock == INVALID_SOCKET)
      // Both the client and the server have closed, terminate the connection
      zdtun_conn_close(tun, conn, CONN_STATUS_CLOSED);

    return rv;
  }

  if((data->th_flags & TH_ACK) && (conn->sock != INVALID_SOCKET)) {
    if(is_keep_alive) {
      debug("TCP KEEPALIVE");

      int val = 1;
      if(setsockopt(conn->sock, SOL_SOCKET, SO_KEEPALIVE, &val, sizeof(val)) != 0)
        error("setsockopt SO_KEEPALIVE failed[%d]: %s", errno, strerror(errno));

      // Assume that the server is alive until its socket is closed.
      build_reply_tcpip(tun, conn, TH_ACK, 0, 0);

      if(send_to_client(tun, conn, TCP_HEADER_LEN) != 0)
        return -1;
    } else {
      // Received ACK from the client, update the window. Take into account
      // the in flight bytes which the client has not ACK-ed yet.
      uint32_t ack_num = ntohl(data->th_ack);
      uint32_t in_flight;

      if(conn->tcp.zdtun_seq >= ack_num)
        in_flight = conn->tcp.zdtun_seq - ack_num;
      else
        // TCP seq wrapped
        in_flight = 0xFFFFFFFF - ack_num + conn->tcp.zdtun_seq + 1;

      uint32_t window = ntohs(data->th_win) << conn->tcp.window_scale;
      conn->tcp.window_size = window - in_flight;

      if(!FD_ISSET(conn->sock, &tun->all_fds) && (conn->tcp.window_size > 0)) {
        log_tcp_window("[%d][Window size: %u] enabling socket", conn->tuple.src_port, conn->tcp.window_size);

        // make the socket selectable again
        FD_SET(conn->sock, &tun->all_fds);
      }
    }
  }

  // check for payload data (avoid sending ACK to an ACK)
  if(pkt->l7_len > 0) {
    if(conn->sock == INVALID_SOCKET) {
      // The server may have closed the connection while the client is still
      // sending data. We should still ACK this data
      debug("Write after server socket closed");

      // send the ACK
      conn->tcp.client_seq += pkt->l7_len;
      build_reply_tcpip(tun, conn, TH_ACK, 0, 0);
      return send_to_client(tun, conn, TCP_HEADER_LEN);
    } else
      // data send is deferred to handle_queued_tcp_data, when socket will be ready for TX
      return enqueue_tcp_data(tun, conn, pkt->l7, pkt->l7_len, data->th_flags);
  }

  return 0;
}

/* ******************************************************* */

static int fill_ipv6_bind_addr(struct sockaddr_in6 *addr) {
  struct addrinfo *ainfo = NULL;
  struct addrinfo hint = {};

  hint.ai_flags = AI_NUMERICHOST | AI_PASSIVE;
  hint.ai_family = AF_INET6;
  hint.ai_socktype = SOCK_DGRAM;
  hint.ai_protocol = IPPROTO_UDP;

  if(getaddrinfo("::0", NULL, &hint, &ainfo) < 0) {
    error("getaddrinfo failed");
    return -1;
  }

  if(!ainfo || (ainfo->ai_family != AF_INET6)) {
    error("getaddrinfo invalid family");
    freeaddrinfo(ainfo);
    return -1;
  }

  *addr = *(struct sockaddr_in6*)ainfo->ai_addr;
  freeaddrinfo(ainfo);
  return 0;
}

/* ******************************************************* */

// UDP socket operations:
//  - bind: picks up a local port for inbound packets. This is normally done
//    by sendto, but here we will possibly reuse an existing port from udp_mapping_t
//    to make STUN to work properly
//  - connect: ensures that the socket can only send/receive from the specified peer.
//    Moreover, it speeds up send/sendto, removing route lookup on each packet
static int bind_and_connect_udp(zdtun_t *tun, zdtun_conn_t *conn) {
  uint8_t ipver = sock_ipver(tun, conn);
  struct sockaddr_in6 bind_addr = {0};
  socklen_t addrlen = (ipver == 4) ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6);

  if(ipver == 6) {
    if(fill_ipv6_bind_addr(&bind_addr) < 0) {
      zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
      return -1;
    }
  }

  uint32_t key = udp_mapping_key(&conn->tuple);
  udp_mapping_t *mapping;

  HASH_FIND(hh, tun->udp_mappings, &key, sizeof(key), mapping);
  if(mapping != NULL) {
    // If the client opens a UDP connection with the same source port of an
    // existing (not purged) connection, then reuse the existing local port.
    // This makes NAT traversal protocols like STUN work.
    if(ipver == 4)
      ((struct sockaddr_in*)&bind_addr)->sin_port = mapping->port;
    else
      bind_addr.sin6_port = mapping->port;
  } // else pick a random local port (see below getsockname)

  // port can be used by multiple sockets (when mapping->num_uses > 1)
  if(setsockopt(conn->sock, SOL_SOCKET, SO_REUSEADDR, &(int){1}, sizeof(int)) < 0) {
    close_with_socket_error(tun, conn, "UDP SO_REUSEADDR");
    return -1;
  }

  if(bind(conn->sock, (struct sockaddr *) &bind_addr, addrlen) == SOCKET_ERROR) {
    close_with_socket_error(tun, conn, "UDP bind");
    return -1;
  }

  struct sockaddr_in6 servaddr = {0};
  fill_conn_sockaddr(tun, conn, &servaddr, &addrlen);

  if(connect(conn->sock, (struct sockaddr *) &servaddr, addrlen) == SOCKET_ERROR) {
    close_with_socket_error(tun, conn, "UDP connect");
    return -1;
  }

  if(mapping == NULL) {
    // random port was requested with bind, get the assigned port number
    if(getsockname(conn->sock, (struct sockaddr *)&bind_addr, &addrlen) < 0) {
      close_with_socket_error(tun, conn, "UDP getsockname");
      return -1;
    }

    safe_alloc(mapping, udp_mapping_t);
    mapping->key = key;
    mapping->port = (ipver == 4) ? ((struct sockaddr_in*)&bind_addr)->sin_port : bind_addr.sin6_port;
    mapping->num_uses = 1;

    HASH_ADD(hh, tun->udp_mappings, key, sizeof(key), mapping);
  } else if(mapping->num_uses < (uint16_t)-1) {
    mapping->num_uses++;
    debug("Reusing UDP port: client=%d, local=%d", htons(conn->tuple.src_port), htons(mapping->port));
  }

  return 0;
}

/* ******************************************************* */

static int handle_udp_fwd(zdtun_t *tun, const zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
  struct udphdr *data = pkt->udp;
  uint8_t ipver = sock_ipver(tun, conn);
  int family = (ipver == 4) ? PF_INET : PF_INET6;

  if(conn->status == CONN_STATUS_NEW) {
    debug("Allocating new UDP socket for port %d", ntohs(data->uh_sport));

    socket_t udp_sock = open_socket(tun, family, SOCK_DGRAM, IPPROTO_UDP);
    if(udp_sock == INVALID_SOCKET) {
      error("Cannot create UDP socket[%d]", socket_errno);
      return -1;
    }

    conn->sock = udp_sock;

    // Check for broadcasts/multicasts
    if(ipver == 4) {
      if(conn->tuple.dst_ip.ip4 == INADDR_BROADCAST) {
        int on = 1;

        debug("UDP4 broadcast detected");

        if(setsockopt(udp_sock, SOL_SOCKET, SO_BROADCAST, &on, sizeof(on)))
          error("UDP setsockopt SO_BROADCAST failed[%d]: %s", errno, strerror(errno));
      }
    } else {
      // Adapted from netguard/udp.c
      if(conn->tuple.dst_ip.ip6.s6_addr[0] == 0xFF) {
        debug("UDP6 broadcast detected");

        int loop = 1; // true
        if(setsockopt(udp_sock, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &loop, sizeof(loop)))
          error("UDP setsockopt IPV6_MULTICAST_LOOP failed[%d]: %s",
                  errno, strerror(errno));

        int ttl = -1; // route default
        if(setsockopt(udp_sock, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &ttl, sizeof(ttl)))
          error("UDP setsockopt IPV6_MULTICAST_HOPS failed[%d]: %s",
                  errno, strerror(errno));

        struct ipv6_mreq mreq6;
        memcpy(&mreq6.ipv6mr_multiaddr, &conn->tuple.dst_ip.ip6, 16);
        mreq6.ipv6mr_interface = INADDR_ANY;

        if(setsockopt(udp_sock, IPPROTO_IPV6, IPV6_ADD_MEMBERSHIP, &mreq6, sizeof(mreq6)))
          error("UDP setsockopt IPV6_ADD_MEMBERSHIP failed[%d]: %s",
                  errno, strerror(errno));
      }
    }

    // TODO exclude broadcast/multicast addresses?
    if(bind_and_connect_udp(tun, conn) < 0)
      return -1;

    conn->status = CONN_STATUS_CONNECTED;
  }

  if(tun->callbacks.account_packet)
    tun->callbacks.account_packet(tun, pkt, 1 /* to zdtun */, conn);

  if(send(conn->sock, pkt->l7, pkt->l7_len, 0) < 0) {
    close_with_socket_error(tun, conn, "UDP sendto");
    return 0;
  }

  check_dns_request(conn, pkt->l7, pkt->l7_len);

  return 0;
}

/* ******************************************************* */

/* NOTE: a collision may occure between ICMP packets seq from host and tunneled packets, we ignore it */
static int handle_icmp_fwd(zdtun_t *tun, const zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
  struct icmphdr *data = pkt->icmp;
  const uint16_t icmp_len = pkt->l4_hdr_len + pkt->l7_len;

  if(conn->status == CONN_STATUS_NEW) {
    int family = (sock_ipver(tun, conn) == 4) ? PF_INET : PF_INET6;
    int proto = (family == PF_INET) ? IPPROTO_ICMP : IPPROTO_ICMPV6;

    /*
     * Either a SOCK_RAW or SOCK_DGRAM can be used. The SOCK_DGRAM, however, does not require root
     * privileges, so it is a better choice (also for Android). See https://lwn.net/Articles/443051
     * for more details.
     *
     * However the SOCK_DGRAM:
     *  - Contrary to the RAW socket, requires a separate socket per ICMP connection.
     *  - The reply received via recv misses the IP header.
     *  - Does not honor all the ICMP header fields (e.g. the ICMP echo ID).
     */
    socket_t icmp_sock = open_socket(tun, family, SOCK_DGRAM, proto);
    debug("Allocating new ICMP socket for id %d", ntohs(data->un.echo.id));

    if(icmp_sock == INVALID_SOCKET) {
      error("Cannot create ICMP socket[%d]", socket_errno);
      conn->status = CONN_STATUS_SOCKET_ERROR;
      return -1;
    }

    conn->sock = icmp_sock;
    conn->status = CONN_STATUS_CONNECTED;
    conn->tuple.src_port = data->un.echo.id;
  }

  debug("ICMP.fw[len=%u] id=%d seq=%d type=%d code=%d", icmp_len, data->un.echo.id,
          data->un.echo.sequence, data->type, data->code);

  if(tun->callbacks.account_packet)
    tun->callbacks.account_packet(tun, pkt, 1 /* to zdtun */, conn);

  struct sockaddr_in6 servaddr = {0};
  socklen_t addrlen;
  fill_conn_sockaddr(tun, conn, &servaddr, &addrlen);

  if(sendto(conn->sock, data, icmp_len, 0, (struct sockaddr*)&servaddr, addrlen) < 0) {
    close_with_socket_error(tun, conn, "ICMP sendto");
    return -1;
  }

  return 0;
}

/* ******************************************************* */

int zdtun_forward(zdtun_t *tun, const zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
  int rv = 0;

  if(conn->status >= CONN_STATUS_CLOSED) {
    debug("Refusing to forward closed connection");
    return 0;
  }

  switch(pkt->tuple.ipproto) {
    case IPPROTO_TCP:
      rv = handle_tcp_fwd(tun, pkt, conn);
      break;
    case IPPROTO_UDP:
      rv = handle_udp_fwd(tun, pkt, conn);
      break;
    case IPPROTO_ICMP:
      rv = handle_icmp_fwd(tun, pkt, conn);
      break;
    default:
      error("Ignoring unhandled IP protocol %d", pkt->tuple.ipproto);
      return -2;
  }

  if(rv == 0) {
    conn->tstamp = zdtun_now(tun);

    if(conn->status == CONN_STATUS_NEW)
      error("Connection status must not be CONN_STATUS_NEW here!");
  }

  return rv;
}

/* ******************************************************* */

zdtun_conn_t* zdtun_easy_forward(zdtun_t *tun, const char *pkt_buf, int pkt_len) {
  zdtun_pkt_t pkt;

  if(zdtun_parse_pkt(tun, pkt_buf, pkt_len, &pkt) != 0) {
    debug("zdtun_easy_forward: zdtun_parse_pkt failed");
    return NULL;
  }

  if(pkt.flags & ZDTUN_PKT_IS_FRAGMENT) {
    debug("TCP: ignoring fragmented IP");
    return NULL;
  }

  uint8_t is_tcp_established = ((pkt.tuple.ipproto == IPPROTO_TCP) &&
    (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK)));

  zdtun_conn_t *conn = zdtun_lookup(tun, &pkt.tuple, !is_tcp_established);

  if(!conn) {
    if(is_tcp_established) {
      debug("TCP: ignoring non SYN connection");
    } else {
      debug("zdtun_lookup failed");
    }

    return NULL;
  }

  if(zdtun_forward(tun, &pkt, conn) != 0) {
    debug("zdtun_forward failed");

    /* Close the connection as soon an any error occurs */
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    return NULL;
  }

  return conn;
}

/* ******************************************************* */

static int handle_icmp_reply(zdtun_t *tun, zdtun_conn_t *conn) {
  int iphdr_len = zdtun_iphdr_len(tun, conn);
  int icmp_len = recv(conn->sock, tun->reply_buf + iphdr_len,
          REPLY_BUF_SIZE - iphdr_len, 0);

  if(icmp_len == SOCKET_ERROR) {
    close_with_socket_error(tun, conn, "ICMP recv");
    return -1;
  }

  if(icmp_len < sizeof(struct icmphdr)) {
    error("ICMP packet too small[%d]", icmp_len);
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    return -1;
  }

  struct icmphdr *data = (struct icmphdr*) &tun->reply_buf[iphdr_len];

  if((data->type != ICMP_ECHO) && (data->type != ICMP_ECHOREPLY) &&
	  (data->type != ICMPv6_ECHO) && (data->type != ICMPv6_ECHOREPLY)) {
    debug("Discarding unsupported ICMP[%d]", data->type);
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    return 0;
  }

  // Reset the correct ID (the kernel changes it)
  data->un.echo.id = conn->tuple.echo_id;

  debug("ICMP.re[len=%d] id=%d seq=%d type=%d code=%d", icmp_len, data->un.echo.id,
          data->un.echo.sequence, data->type, data->code);

  conn->tstamp = zdtun_now(tun);

  uint8_t ipver = sock_ipver(tun, conn);

  data->checksum = 0;
  if(ipver == 4)
    data->checksum = ~calc_checksum(data->checksum, (u_int8_t*)data, icmp_len);

  zdtun_make_iphdr(tun, conn, tun->reply_buf, icmp_len);

  if(ipver == 6)
    data->checksum = zdtun_l3_checksum(tun, conn, tun->reply_buf, (char*)data, icmp_len);

  return send_to_client(tun, conn, icmp_len);
}

/* ******************************************************* */

static int handle_tcp_reply(zdtun_t *tun, zdtun_conn_t *conn) {
  int iphdr_len = zdtun_iphdr_len(tun, conn);
  char *payload_ptr = tun->reply_buf + iphdr_len + TCP_HEADER_LEN;
  int to_recv = min(conn->tcp.window_size, conn->tcp.mss);
  int l4_len = recv(conn->sock, payload_ptr, to_recv, 0);

  conn->tstamp = zdtun_now(tun);

  if(l4_len == SOCKET_ERROR)
    return close_with_socket_error(tun, conn, "TCP recv");
  else if(l4_len == 0) {
    debug("Server socket closed");

    if(!conn->tcp.fin_ack_sent) {
      tcp_socket_fin_ack(tun, conn);
      conn->tcp.fin_ack_sent = 1;
    }

    // close the socket, otherwise select will keep triggering
    // The client communication can still go on (e.g. client sending ACK to FIN+ACK)
    close_socket(tun, conn->sock);
    conn->sock = INVALID_SOCKET;

    if(conn->tcp.client_closed)
      // Both the client and the server have closed, terminate the connection
      zdtun_conn_close(tun, conn, CONN_STATUS_CLOSED);

    return 0;
  }

  if(socks5_in_progress(conn)) {
    int rv = handle_socks5_reply(tun, conn, payload_ptr, l4_len);

    if(rv != 0)
      return(rv);

    if(conn->socks5_status == SOCKS5_ESTABLISHED) {
      // SOCKS5 handshake completed, send the SYN+ACK
      rv = send_syn_ack(tun, conn);
    }

    return rv;
  }

  if(conn->tcp.window_size < l4_len) {
    error("Invalid state: TCP windows_size < l4_len");
    zdtun_conn_close(tun, conn, CONN_STATUS_ERROR);
    return -1;
  }

  int flags = TH_ACK;

#ifndef WIN32
  // Since we cannot determine server message bounds, we assume that
  // message ends when no more data is available in the socket buffer.
  int count = 0;
  ioctl(conn->sock, FIONREAD, &count);

  if(count == 0)
    flags |= TH_PUSH;
#endif

  // NAT back the TCP port and reconstruct the TCP header
  build_reply_tcpip(tun, conn, flags, l4_len, 0);

  if(send_to_client(tun, conn, l4_len + TCP_HEADER_LEN) == 0) {
    conn->tcp.zdtun_seq += l4_len;
    conn->tcp.window_size -= l4_len;

    if(conn->tcp.window_size == 0) {
      log_tcp_window("[%d] Zero window size detected [l4=%d], disabling socket",
        conn->tuple.src_port, l4_len);

      // stop receiving updates for the socket, until the TCP window is updated
      FD_CLR(conn->sock, &tun->all_fds);
    }
  } else
    return -1;

  return 0;
}

/* ******************************************************* */

static int handle_udp_reply(zdtun_t *tun, zdtun_conn_t *conn) {
  int iphdr_len = zdtun_iphdr_len(tun, conn);
  char *payload_ptr = tun->reply_buf + iphdr_len + sizeof(struct udphdr);
  int l4_len = recv(conn->sock, payload_ptr, REPLY_BUF_SIZE-iphdr_len-sizeof(struct udphdr), 0);

  if(l4_len == SOCKET_ERROR) {
    close_with_socket_error(tun, conn, "UDP recv");
    return -1;
  }

  // Reconstruct the UDP header
  int l3_len = l4_len + sizeof(struct udphdr);
  struct udphdr *data = (struct udphdr*) (tun->reply_buf + iphdr_len);
  data->uh_ulen = htons(l3_len);
  data->uh_sport = conn->tuple.dst_port;

  // NAT back the UDP port
  data->uh_dport = conn->tuple.src_port;

  zdtun_make_iphdr(tun, conn, tun->reply_buf, l3_len);

  // UDP checksum mandatory only for IPv6. Keep it 0 for IPv4 to speed up things.
  data->uh_sum = 0;
  if(sock_ipver(tun, conn) != 4)
    data->uh_sum = zdtun_l3_checksum(tun, conn, tun->reply_buf, (char*)data, l3_len);

  int rv = send_to_client(tun, conn, l3_len);

  if(rv == 0) {
    // ok
    conn->tstamp = zdtun_now(tun);

    check_dns_purge(tun, conn, payload_ptr, l4_len);
  }

  return rv;
}

/* ******************************************************* */

static int handle_tcp_connect_async(zdtun_t *tun, zdtun_conn_t *conn) {
  int optval = -1;
  socklen_t optlen = sizeof (optval);
  int rv = 0;

  if(getsockopt(conn->sock, SOL_SOCKET, SO_ERROR, (char*)&optval, &optlen) == SOCKET_ERROR) {
    error("getsockopt failed: %d", socket_errno);

    zdtun_conn_close(tun, conn, CONN_STATUS_SOCKET_ERROR);
    rv = -1;
  } else {
    if(optval == 0) {
      debug("TCP non-blocking socket connected");
      rv = tcp_socket_syn(tun, conn);
      conn->tstamp = zdtun_now(tun);
    } else {
#ifndef WIN32
      errno = optval;
#endif
      close_with_socket_error(tun, conn, "TCP non-blocking connect");
      rv = -1;
    }
  }

  return rv;
}

/* ******************************************************* */

static int handle_queued_tcp_data(zdtun_t *tun, zdtun_conn_t *conn) {
  int sent = 0;

  while(conn->tcp.tx_queue) {
    tcp_data_t *item = conn->tcp.tx_queue;

    // MSG_MORE buffers packets until the TH_PUSH is set
    // Use MSG_DONTWAIT to avoid blocking on large uploads
    int flags = ((item->flags & TH_PUSH) ? 0 : MSG_MORE) | MSG_DONTWAIT;
    int to_send = item->len - item->sofar;
    int rv = send(conn->sock, item->data + item->sofar, to_send, flags);

    if(rv < 0) {
      if((errno != EWOULDBLOCK) && (errno != EAGAIN))
        return close_with_socket_error(tun, conn, "TCP send");

      debug("EAGAIN hit");
      break;
    } else if(rv != to_send) {
      log_partial_send("TCP partial send: sent %d, still remaining %d", rv, to_send - rv);

      item->sofar += rv;
      sent += rv;
      break;
    } else {
      sent += to_send;
      conn->tcp.tx_queue = item->next;
      free(item);
    }
  }

  if(!conn->tcp.tx_queue)
    // no more data to send
    FD_CLR(conn->sock, &tun->write_fds);

  if(sent > 0) {
    // ACK the sent packets
    conn->tcp.client_seq += sent;
    conn->tcp.tx_queue_size -= sent;
    build_reply_tcpip(tun, conn, TH_ACK, 0, 0);

    if(send_to_client(tun, conn, TCP_HEADER_LEN) < 0)
      return -1;
  }

  return 0;
}

/* ******************************************************* */

int zdtun_handle_fd(zdtun_t *tun, const fd_set *rd_fds, const fd_set *wr_fds) {
  int rv = 0;
  zdtun_conn_t *conn, *tmp;

  HASH_ITER(hh, tun->conn_table, conn, tmp) {
    uint8_t ipproto = conn->tuple.ipproto;

    if(conn->sock == INVALID_SOCKET)
      continue;

    if(FD_ISSET(conn->sock, rd_fds)) {
      if(ipproto == IPPROTO_TCP)
        rv = handle_tcp_reply(tun, conn);
      else if(ipproto == IPPROTO_UDP)
        rv = handle_udp_reply(tun, conn);
      else if(ipproto == IPPROTO_ICMP)
        rv = handle_icmp_reply(tun, conn);
      else
        error("Unhandled socket.rd proto: %d", ipproto);
    } else if(FD_ISSET(conn->sock, wr_fds)) {
      if(ipproto == IPPROTO_TCP) {
        if(conn->tcp.tx_queue)
          rv = handle_queued_tcp_data(tun, conn);
        else
          rv = handle_tcp_connect_async(tun, conn);
      } else
        error("Unhandled socket.wr proto: %d", ipproto);
    }

    if(rv != 0)
      break;
  }

  return rv;
}

/* ******************************************************* */

// negative, zero, or positive <=> A before, equal to, or after B
static inline int zdtun_conn_cmp_timestamp_asc(zdtun_conn_t *a, zdtun_conn_t *b) {
  return(a->tstamp - b->tstamp);
}

// purges old connections. Harvests the closed connections (set by close_conn)
// and purges them (assuming no dangling pointers around).
void zdtun_purge_expired(zdtun_t *tun) {
  zdtun_conn_t *conn, *tmp;
  time_t now = zdtun_now(tun);

  /* Purge by idleness */
  HASH_ITER(hh, tun->conn_table, conn, tmp) {
    time_t timeout = 0;

    switch(conn->tuple.ipproto) {
    case IPPROTO_TCP:
      timeout = TCP_TIMEOUT_SEC;
      break;
    case IPPROTO_UDP:
      timeout = UDP_TIMEOUT_SEC;
      break;
    case IPPROTO_ICMP:
      timeout = ICMP_TIMEOUT_SEC;
      break;
    }

    if((conn->status >= CONN_STATUS_CLOSED) || (now >= (timeout + conn->tstamp))) {
      debug("IDLE (type=%d)", conn->tuple.ipproto);
      destroy_conn(tun, conn);
    }
  }

  if(tun->stats.num_open_sockets >= MAX_NUM_SOCKETS) {
    int to_purge = tun->stats.num_open_sockets - NUM_SOCKETS_AFTER_PURGE;

    debug("FORCE PURGE %d items", to_purge);

    HASH_SORT(tun->conn_table, zdtun_conn_cmp_timestamp_asc);

    HASH_ITER(hh, tun->conn_table, conn, tmp) {
      if(to_purge == 0)
        break;

      destroy_conn(tun, conn);
      to_purge--;
    }
  }
}

/* ******************************************************* */

int zdtun_iter_connections(zdtun_t *tun, zdtun_conn_iterator_t iterator, void *userdata) {
  zdtun_conn_t *conn, *tmp;

  HASH_ITER(hh, tun->conn_table, conn, tmp) {
    // Do not iterate closed connections. User may have already free some data in
    // on_connection_close so this may lead to invalid memory access.
    if(conn->status < CONN_STATUS_CLOSED) {
      if(iterator(tun, conn, userdata) != 0)
        return(1);
    }
  }

  return(0);
}

/* ******************************************************* */

int zdtun_get_num_connections(zdtun_t *tun) {
  return(tun->stats.num_tcp_conn + tun->stats.num_udp_conn + tun->stats.num_icmp_conn);
}

/* ******************************************************* */

void zdtun_get_stats(zdtun_t *tun, zdtun_statistics_t *stats) {
  *stats = tun->stats;
}

/* ******************************************************* */

const char* zdtun_proto2str(int ipproto) {
  switch (ipproto) {
    case IPPROTO_TCP:
        return "TCP";
    case IPPROTO_UDP:
        return "UDP";
    case IPPROTO_ICMP:
        return "ICMP";
    default:
        return "Unknown";
  }
}

/* ******************************************************* */

char* zdtun_5tuple2str(const zdtun_5tuple_t *tuple, char *buf, size_t bufsize) {
  char srcip[INET6_ADDRSTRLEN];
  char dstip[INET6_ADDRSTRLEN];
  int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

  inet_ntop(family, &tuple->src_ip, srcip, sizeof(srcip));
  inet_ntop(family, &tuple->dst_ip, dstip, sizeof(dstip));

  snprintf(buf, bufsize, "[%s%c] %s:%u -> %s:%u",
           zdtun_proto2str(tuple->ipproto),
           (tuple->ipver == 4) ? '4' : '6',
           srcip, ntohs(tuple->src_port),
           dstip, ntohs(tuple->dst_port));

  return buf;
}

/* ******************************************************* */

const char* zdtun_conn_status2str(zdtun_conn_status_t status) {
  switch(status) {
    case CONN_STATUS_NEW:
      return "NEW";
    case CONN_STATUS_CONNECTING:
      return "CONNECTING";
    case CONN_STATUS_CONNECTED:
      return "CONNECTED";
    case CONN_STATUS_CLOSED:
      return "CLOSED";
    case CONN_STATUS_ERROR:
      return "ERROR";
    case CONN_STATUS_SOCKET_ERROR:
      return "SOCKET_ERROR";
    case CONN_STATUS_CLIENT_ERROR:
      return "CLIENT_ERROR";
    case CONN_STATUS_RESET:
      return "RESET";
    case CONN_STATUS_UNREACHABLE:
      return "UNREACHABLE";
    case CONN_STATUS_SOCKS5_ERROR:
      return "SOCKS5_ERROR";
  }

  return "UNKNOWN";
}

#include "socks5.c"
