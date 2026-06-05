//#include <netinet/in.h>
//#include <netinet/ip.h>
//#include <netinet/ip_icmp.h>
//#include <netinet/udp.h>
//#include <netinet/tcp.h>
//#include <linux/ipv6.h>

#ifndef __NET_HEADERS_H__
#define __NET_HEADERS_H__

#ifdef WIN32

#define IPPROTO_ICMP            1
#define IPPROTO_TCP             6
#define IPPROTO_UDP             17
#define IPPROTO_ICMPV6          58

#endif

PACK_ON
struct iphdr
{
#if defined(_LITTLE_ENDIAN)
    u_int8_t ihl:4;
    u_int8_t version:4;
#elif defined(_BIG_ENDIAN)
    u_int8_t version:4;
    u_int8_t ihl:4;
#else
#error "Please fix endianess"
#endif
  uint8_t tos;
  uint16_t tot_len;
  uint16_t id;
  uint16_t frag_off;
  uint8_t ttl;
  uint8_t protocol;
  uint16_t check;
  uint32_t saddr;
  uint32_t daddr;
  /*The options start here. */
} PACK_OFF;

#define ICMP_ECHOREPLY          0
#define ICMP_ECHO               8
#define ICMPv6_ECHO             128
#define ICMPv6_ECHOREPLY        129

PACK_ON
struct icmphdr
{
  uint8_t type;         /* message type */
  uint8_t code;         /* type sub-code */
  uint16_t checksum;
  union
  {
    struct
    {
      uint16_t  id;
      uint16_t  sequence;
    } echo;                     /* echo datagram */
    uint32_t    gateway;        /* gateway address */
    struct
    {
      uint16_t  __glibc_reserved;
      uint16_t  mtu;
    } frag;                     /* path mtu discovery */
  } un;
} PACK_OFF;

PACK_ON
struct udphdr
{
  uint16_t uh_sport;        /* source port */
  uint16_t uh_dport;        /* destination port */
  uint16_t uh_ulen;         /* udp length */
  uint16_t uh_sum;          /* udp checksum */
} PACK_OFF;

typedef uint32_t tcp_seq;

PACK_ON
struct tcphdr
{
  uint16_t th_sport;      /* source port */
  uint16_t th_dport;      /* destination port */
  tcp_seq th_seq;         /* sequence number */
  tcp_seq th_ack;         /* acknowledgement number */
#if defined(_LITTLE_ENDIAN)
  uint8_t th_x2:4;        /* (unused) */
  uint8_t th_off:4;       /* data offset */
#else
#if defined(_BIG_ENDIAN)
  uint8_t th_off:4;       /* data offset */
  uint8_t th_x2:4;        /* (unused) */
#endif
#endif
  uint8_t th_flags;
#define TH_FIN 0x01
#define TH_SYN 0x02
#define TH_RST 0x04
#define TH_PUSH        0x08
#define TH_ACK 0x10
#define TH_URG 0x20
  uint16_t th_win;        /* window */
  uint16_t th_sum;        /* checksum */
  uint16_t th_urp;        /* urgent pointer */
} PACK_OFF;

PACK_ON
struct ipv6_hdr {
#if defined(_LITTLE_ENDIAN)
  uint8_t priority:4;
  uint8_t version:4;
#elif defined(_BIG_ENDIAN)
  uint8_t version:4;
  uint8_t priority:4;
#else
#error "Please fix endianess"
#endif
  uint8_t flow_lbl[3];

  uint16_t payload_len;
  uint8_t nexthdr;
  uint8_t hop_limit;

  struct in6_addr saddr;
  struct in6_addr daddr;
} PACK_OFF;

#endif
