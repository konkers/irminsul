/* ----------------------------------------------------------------------------
 * Zero Dep Tunnel: VPN library without dependencies
 * ----------------------------------------------------------------------------
 *
 * Copyright (C) 2021 - Emanuele Faranda
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

#ifndef __ZDTUN_SOCKS5_H__
#define __ZDTUN_SOCKS5_H__

typedef enum {
  SOCKS5_HELLO = 0,
  SOCKS5_AUTH,
  SOCKS5_CONNECTING,
  SOCKS5_SKIP_BND,
  SOCKS5_ESTABLISHED
} socks5_status_t;

#define socks5_in_progress(c) ((c->proxy_mode == PROXY_SOCKS5)\
  && (c->socks5_status != SOCKS5_ESTABLISHED))

int socks5_connect(zdtun_t *tun, zdtun_conn_t *conn);
int handle_socks5_reply(zdtun_t *tun, zdtun_conn_t *conn, char *data, int len);

#endif
