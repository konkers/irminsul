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

#ifndef __ZDTUN_UTILS_H__
#define __ZDTUN_UTILS_H__

typedef enum {
  CON_MODE_CLIENT,
  CON_MODE_SERVER
} con_mode;

typedef struct {
  con_mode mode;
  const char *address;
  int port;
  socket_t socket;
} con_mode_info;

void con_parse_args(char **argv, con_mode_info *info);
socket_t con_wait_connection(con_mode_info *info, struct sockaddr_in *cli_addr);
void con_send(socket_t sock, char *data, u_int32_t len);
u_int32_t con_recv(socket_t sock, char *data, u_int32_t len);

u_int16_t calc_checksum(u_int16_t start, const u_int8_t *buffer, u_int16_t length);
char* ipv4str(u_int32_t addr, char *buf);
void xor_encdec(char *data, int data_len, char *key);
int open_tun(const char *tun_dev, const char*ip, const char *netmask);
void cmd(const char *fmt, ...);
u_int32_t get_default_gw();
int get_default_gw6_and_iface(struct in6_addr *gw, char *iface);

#endif
