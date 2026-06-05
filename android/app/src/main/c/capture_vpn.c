#include "capture_vpn.h"
#include <android/log.h>
#include <unistd.h>
#include <sys/select.h>
#include <string.h>
#include <errno.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <netinet/ip.h>
#include <time.h>

#define TAG "capture_vpn"
#define MAX_PKT_SIZE 65535
#define VIRTUAL_DNS_IP4 "10.215.173.2"
#define VIRTUAL_DNS_IP6 "fd00:2:fd00:1:fd00:1:fd00:2"
#define DNS_PORT 53
#define DOT_PORT 853
#define SELECT_TIMEOUT_SEC 0
#define SELECT_TIMEOUT_USEC 100000
#define STATS_UPDATE_INTERVAL_SEC 1

static const char *KNOWN_DNS_IPS[] = {
    "8.8.8.8", "8.8.4.4",
    "1.1.1.1", "1.0.0.1",
    "9.9.9.9",
    "223.5.5.5", "223.6.6.6",
    "119.29.29.29", "182.254.116.116",
    "114.114.114.114", "114.114.115.115",
    "180.76.76.76",
    "208.67.222.222", "208.67.220.220",
    NULL
};

static zdtun_ip_t known_dns_ips[16];
static int known_dns_ips_count = 0;

static void log_info(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, TAG, fmt, args);
    va_end(args);
}

static void log_error(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, TAG, fmt, args);
    va_end(args);
}

static void init_known_dns_ips() {
    known_dns_ips_count = 0;
    for (int i = 0; KNOWN_DNS_IPS[i] != NULL && known_dns_ips_count < 16; i++) {
        zdtun_ip_t ip;
        if (zdtun_parse_ip(KNOWN_DNS_IPS[i], &ip) == 0) {
            known_dns_ips[known_dns_ips_count++] = ip;
        }
    }
    log_info("Loaded %d known DNS server IPs", known_dns_ips_count);
}

static int is_known_dns_ip(const zdtun_5tuple_t *tuple) {
    if (tuple->ipver != 4) return 0;
    for (int i = 0; i < known_dns_ips_count; i++) {
        if (tuple->dst_ip.ip4 == known_dns_ips[i].ip4)
            return 1;
    }
    return 0;
}

static void notify_packet_to_java(capture_ctx_t *ctx, const char *data, int len) {
    JNIEnv *env = ctx->jni_env;
    if (!env || !ctx->on_packet_mid || !ctx->capture_service) return;

    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)data);
        (*env)->CallVoidMethod(env, ctx->capture_service, ctx->on_packet_mid, arr);
        (*env)->DeleteLocalRef(env, arr);
    }
}

static void notify_stats_to_java(capture_ctx_t *ctx) {
    JNIEnv *env = ctx->jni_env;
    if (!env || !ctx->on_stats_mid || !ctx->capture_service) return;

    (*env)->CallVoidMethod(env, ctx->capture_service, ctx->on_stats_mid,
                           (jlong)ctx->bytes_sent, (jlong)ctx->bytes_received,
                           (jint)ctx->num_connections);
}

static int protect_socket_via_java(capture_ctx_t *ctx, int sock_fd) {
    JNIEnv *env = ctx->jni_env;
    if (!env || !ctx->protect_socket_mid || !ctx->capture_service) return 0;

    return (*env)->CallBooleanMethod(env, ctx->capture_service, ctx->protect_socket_mid, sock_fd);
}

static int is_virtual_dns4(capture_ctx_t *ctx, const zdtun_5tuple_t *tuple) {
    zdtun_ip_t dst_ip;
    memcpy(&dst_ip, &tuple->dst_ip, sizeof(zdtun_ip_t));
    return (tuple->ipver == 4 && ctx->virtual_dns_ip4.ip4 == dst_ip.ip4);
}

static int is_virtual_dns6(capture_ctx_t *ctx, const zdtun_5tuple_t *tuple) {
    if (tuple->ipver != 6) return 0;
    return (memcmp(&ctx->virtual_dns_ip6.ip6, &tuple->dst_ip.ip6, sizeof(struct in6_addr)) == 0);
}

static int remote2vpn(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn_info) {
    capture_ctx_t *ctx = (capture_ctx_t *)zdtun_userdata(tun);

    if (!ctx->running) return -1;

    int written = write(ctx->tunfd, pkt->buf, pkt->len);
    if (written < 0) {
        log_error("write to TUN failed: %s", strerror(errno));
        return -1;
    }
    if (written != pkt->len) {
        log_error("partial write to TUN: %d/%d", written, pkt->len);
        return -1;
    }

    return 0;
}

static void account_packet(zdtun_t *tun, const struct zdtun_pkt *pkt,
                           uint8_t to_zdtun, const zdtun_conn_t *conn_info) {
    capture_ctx_t *ctx = (capture_ctx_t *)zdtun_userdata(tun);

    if (!ctx->running) return;

    if (to_zdtun) {
        ctx->bytes_sent += pkt->len;
    } else {
        ctx->bytes_received += pkt->len;
    }

    if (pkt->l7_len > 0 && pkt->l7) {
        notify_packet_to_java(ctx, pkt->buf, pkt->len);
    }

    struct timespec ts;
    if (!clock_gettime(CLOCK_MONOTONIC_COARSE, &ts)) {
        uint64_t now = (uint64_t)ts.tv_sec;
        if (now - ctx->last_stats_time >= STATS_UPDATE_INTERVAL_SEC) {
            ctx->last_stats_time = now;
            zdtun_statistics_t stats;
            zdtun_get_stats(tun, &stats);
            ctx->num_connections = stats.num_tcp_conn + stats.num_udp_conn + stats.num_icmp_conn;
            notify_stats_to_java(ctx);
        }
    }
}

static void on_socket_open(zdtun_t *tun, socket_t socket) {
    capture_ctx_t *ctx = (capture_ctx_t *)zdtun_userdata(tun);
    protect_socket_via_java(ctx, socket);
}

static void on_socket_close(zdtun_t *tun, socket_t socket) {
}

static int on_connection_open(zdtun_t *tun, zdtun_conn_t *conn_info) {
    return 0;
}

static void on_connection_close(zdtun_t *tun, const zdtun_conn_t *conn_info) {
}

static void check_dns_req_allowed(zdtun_t *tun, zdtun_conn_t *conn, const zdtun_pkt_t *pkt) {
    capture_ctx_t *ctx = (capture_ctx_t *)zdtun_userdata(tun);
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);

    if (tuple->ipproto != IPPROTO_UDP)
        return;

    if (tuple->dst_port != htons(DNS_PORT))
        return;

    if (tuple->ipver == 4 && is_virtual_dns4(ctx, tuple)) {
        if (ctx->real_dns_ip4.ip4 != 0) {
            zdtun_conn_dnat(conn, &ctx->real_dns_ip4, htons(ctx->real_dns_port), 4);
            log_info("DNS DNAT v4: redirecting to real DNS server");
        } else {
            log_error("DNS DNAT v4: no real DNS server configured");
        }
    } else if (tuple->ipver == 6 && is_virtual_dns6(ctx, tuple)) {
        if (ctx->real_dns_ipver == 6) {
            zdtun_conn_dnat(conn, &ctx->real_dns_ip6, htons(DNS_PORT), 6);
            log_info("DNS DNAT v6: redirecting to real DNS server");
        } else {
            log_error("DNS DNAT v6: no real DNS server configured");
        }
    }
}

static int is_private_dns_conn(const zdtun_5tuple_t *tuple) {
    if (tuple->ipproto == IPPROTO_TCP && tuple->dst_port == htons(DOT_PORT))
        return 1;

    if (tuple->ipproto == IPPROTO_TCP && is_known_dns_ip(tuple) &&
        tuple->dst_port != htons(DNS_PORT) && tuple->dst_port != htons(80) &&
        tuple->dst_port != htons(443))
        return 1;

    return 0;
}

static void spoof_dns_reply(capture_ctx_t *ctx, const zdtun_pkt_t *pkt) {
    if (pkt->l7_len < 12)
        return;

    const struct udphdr *udp = pkt->udp;
    uint16_t dns_len = ntohs(udp->uh_ulen) - sizeof(struct udphdr);
    if (dns_len < 12)
        return;

    const uint8_t *dns = (const uint8_t *)pkt->l7;
    if ((dns[2] & 0x80) != 0)
        return;

    uint16_t flags = 0x8180;
    uint16_t qdcount_val = dns[4] | dns[5];
    uint16_t ancount_val = 1;

    uint8_t reply[MAX_PKT_SIZE];
    int offset = 0;

    if (pkt->tuple.ipver == 4) {
        struct iphdr ip_hdr;
        memset(&ip_hdr, 0, sizeof(ip_hdr));
        ip_hdr.version = 4;
        ip_hdr.ihl = 5;
        ip_hdr.tos = 0;
        ip_hdr.id = 0;
        ip_hdr.frag_off = 0;
        ip_hdr.ttl = 64;
        ip_hdr.protocol = IPPROTO_UDP;
        ip_hdr.saddr = pkt->ip4->daddr;
        ip_hdr.daddr = pkt->ip4->saddr;

        int query_end = 12;
        for (uint16_t q = 0; q < qdcount_val && query_end < dns_len; q++) {
            while (query_end < dns_len && dns[query_end] != 0) query_end += dns[query_end] + 1;
            query_end += 5;
        }

        uint16_t answer_len = 16;
        uint16_t total_dns_len = query_end + answer_len;
        uint16_t total_udp_len = sizeof(struct udphdr) + total_dns_len;
        uint16_t total_ip_len = sizeof(struct iphdr) + total_udp_len;

        ip_hdr.tot_len = htons(total_ip_len);
        ip_hdr.check = 0;

        memcpy(reply + offset, &ip_hdr, sizeof(struct iphdr));
        offset += sizeof(struct iphdr);

        struct udphdr reply_udp;
        memset(&reply_udp, 0, sizeof(reply_udp));
        reply_udp.uh_sport = udp->uh_dport;
        reply_udp.uh_dport = udp->uh_sport;
        reply_udp.uh_ulen = htons(total_udp_len);
        reply_udp.uh_sum = 0;

        memcpy(reply + offset, &reply_udp, sizeof(struct udphdr));
        offset += sizeof(struct udphdr);

        memcpy(reply + offset, dns, query_end);
        offset += query_end;

        uint8_t answer[16];
        memset(answer, 0, sizeof(answer));
        answer[0] = 0xc0; answer[1] = 0x0c;
        answer[2] = 0x00; answer[3] = 0x01;
        answer[4] = 0x00; answer[5] = 0x01;
        answer[6] = 0x00; answer[7] = 0x00;
        answer[8] = 0x00; answer[9] = 0x3c;
        answer[10] = 0x00; answer[11] = 0x04;
        answer[12] = 0; answer[13] = 0;
        answer[14] = 0; answer[15] = 0;

        memcpy(reply + offset, answer, 16);
        offset += 16;

        reply[sizeof(struct iphdr) + sizeof(struct udphdr) + 2] = (flags >> 8) & 0xff;
        reply[sizeof(struct iphdr) + sizeof(struct udphdr) + 3] = flags & 0xff;
        reply[sizeof(struct iphdr) + sizeof(struct udphdr) + 6] = (ancount_val >> 8) & 0xff;
        reply[sizeof(struct iphdr) + sizeof(struct udphdr) + 7] = ancount_val & 0xff;

        int written = write(ctx->tunfd, reply, offset);
        if (written > 0)
            log_info("Spoofed DNS reply sent (0.0.0.0)");
    }
}

void capture_set_dns_server(capture_ctx_t *ctx, const char *dns_ip, uint16_t dns_port, uint8_t ipver) {
    if (!ctx || !dns_ip) return;

    if (ipver == 4) {
        zdtun_parse_ip(dns_ip, &ctx->real_dns_ip4);
        ctx->real_dns_port = dns_port;
        ctx->real_dns_ipver = 4;

        char ipstr[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &ctx->real_dns_ip4, ipstr, sizeof(ipstr));
        log_info("Real DNS v4 server set: %s:%d", ipstr, dns_port);
    } else if (ipver == 6) {
        zdtun_parse_ip(dns_ip, &ctx->real_dns_ip6);
        ctx->real_dns_ipver = 6;

        char ipstr[INET6_ADDRSTRLEN];
        inet_ntop(AF_INET6, &ctx->real_dns_ip6, ipstr, sizeof(ipstr));
        log_info("Real DNS v6 server set: %s", ipstr);
    }
}

int cache_jni_methods(capture_ctx_t *ctx) {
    JNIEnv *env = ctx->jni_env;
    if (!env) return -1;

    jclass cls = (*env)->GetObjectClass(env, ctx->capture_service);
    if (!cls) {
        log_error("Failed to get CaptureService class");
        return -1;
    }

    ctx->on_packet_mid = (*env)->GetMethodID(env, cls, "onPacketCaptured", "([B)V");
    if (!ctx->on_packet_mid) {
        log_error("Failed to find onPacketCaptured method");
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    ctx->protect_socket_mid = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
    if (!ctx->protect_socket_mid) {
        log_error("Failed to find protectSocket method");
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    ctx->on_stats_mid = (*env)->GetMethodID(env, cls, "onCaptureStats", "(JJI)V");
    if (!ctx->on_stats_mid) {
        log_error("Failed to find onCaptureStats method");
        (*env)->DeleteLocalRef(env, cls);
        return -1;
    }

    (*env)->DeleteLocalRef(env, cls);
    return 0;
}

int run_vpn_loop(capture_ctx_t *ctx) {
    char pkt_buf[MAX_PKT_SIZE];
    fd_set rd_fds, wr_fds;
    struct timeval tv;
    int max_fd;

    zdtun_parse_ip(VIRTUAL_DNS_IP4, &ctx->virtual_dns_ip4);
    zdtun_parse_ip(VIRTUAL_DNS_IP6, &ctx->virtual_dns_ip6);

    init_known_dns_ips();

    if (ctx->real_dns_ip4.ip4 == 0) {
        log_error("No DNS server configured, DNS resolution will fail");
    }

    struct zdtun_callbacks callbacks = {
        .send_client = remote2vpn,
        .account_packet = account_packet,
        .on_socket_open = on_socket_open,
        .on_socket_close = on_socket_close,
        .on_connection_open = on_connection_open,
        .on_connection_close = on_connection_close,
    };

    ctx->zdt = zdtun_init(&callbacks, ctx);
    if (!ctx->zdt) {
        log_error("zdtun_init failed");
        return -1;
    }

    zdtun_set_mtu(ctx->zdt, 1500);

    struct timespec ts;
    if (!clock_gettime(CLOCK_MONOTONIC_COARSE, &ts))
        ctx->last_stats_time = (uint64_t)ts.tv_sec;

    log_info("VPN capture loop started, tunfd=%d", ctx->tunfd);

    while (ctx->running) {
        FD_ZERO(&rd_fds);
        FD_ZERO(&wr_fds);

        FD_SET(ctx->tunfd, &rd_fds);
        max_fd = ctx->tunfd;

        int zdtun_max_fd;
        fd_set zdtun_rd, zdtun_wr;
        zdtun_fds(ctx->zdt, &zdtun_max_fd, &zdtun_rd, &zdtun_wr);

        for (int fd = 0; fd <= zdtun_max_fd; fd++) {
            if (FD_ISSET(fd, &zdtun_rd)) {
                FD_SET(fd, &rd_fds);
                if (fd > max_fd) max_fd = fd;
            }
            if (FD_ISSET(fd, &zdtun_wr)) {
                FD_SET(fd, &wr_fds);
                if (fd > max_fd) max_fd = fd;
            }
        }

        tv.tv_sec = SELECT_TIMEOUT_SEC;
        tv.tv_usec = SELECT_TIMEOUT_USEC;

        int sel = select(max_fd + 1, &rd_fds, &wr_fds, NULL, &tv);
        if (sel < 0) {
            if (errno == EINTR) continue;
            log_error("select failed: %s", strerror(errno));
            break;
        }

        if (sel == 0) {
            zdtun_purge_expired(ctx->zdt);
            continue;
        }

        if (FD_ISSET(ctx->tunfd, &rd_fds)) {
            ssize_t pkt_len = read(ctx->tunfd, pkt_buf, sizeof(pkt_buf));
            if (pkt_len > 0) {
                zdtun_pkt_t pkt;

                if (zdtun_parse_pkt(ctx->zdt, pkt_buf, (int)pkt_len, &pkt) != 0)
                    goto housekeeping;

                if (pkt.flags & ZDTUN_PKT_IS_FRAGMENT)
                    goto housekeeping;

                uint8_t is_tcp_established = ((pkt.tuple.ipproto == IPPROTO_TCP) &&
                    (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK)));

                zdtun_conn_t *conn = zdtun_lookup(ctx->zdt, &pkt.tuple, !is_tcp_established);
                if (!conn)
                    goto housekeeping;

                if (zdtun_conn_get_status(conn) == CONN_STATUS_NEW)
                    check_dns_req_allowed(ctx->zdt, conn, &pkt);

                if (is_private_dns_conn(&pkt.tuple)) {
                    zdtun_conn_close(ctx->zdt, conn, CONN_STATUS_CLOSED);
                    log_info("Blocked Private DNS (DoT) connection on port %d", ntohs(pkt.tuple.dst_port));
                    goto housekeeping;
                }

                if (pkt.tuple.ipproto == IPPROTO_UDP && pkt.tuple.dst_port == htons(DNS_PORT) &&
                    is_known_dns_ip(&pkt.tuple) && !is_virtual_dns4(ctx, &pkt.tuple)) {
                    spoof_dns_reply(ctx, &pkt);
                    zdtun_conn_close(ctx->zdt, conn, CONN_STATUS_CLOSED);
                    log_info("Blocked direct DNS query to known DNS server");
                    goto housekeeping;
                }

                if (zdtun_forward(ctx->zdt, &pkt, conn) != 0) {
                    zdtun_conn_close(ctx->zdt, conn, CONN_STATUS_ERROR);
                    goto housekeeping;
                }
            } else if (pkt_len == 0) {
                log_info("TUN fd closed");
                break;
            } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                log_error("read from TUN failed: %s", strerror(errno));
                break;
            }
        } else {
            zdtun_handle_fd(ctx->zdt, &rd_fds, &wr_fds);
        }

housekeeping:
        zdtun_purge_expired(ctx->zdt);
    }

    log_info("VPN capture loop ended");

    zdtun_finalize(ctx->zdt);
    ctx->zdt = NULL;

    return 0;
}

capture_ctx_t *capture_ctx_create(JavaVM *java_vm, jobject capture_service, jint sdk_ver) {
    capture_ctx_t *ctx = (capture_ctx_t *)calloc(1, sizeof(capture_ctx_t));
    if (!ctx) return NULL;

    ctx->java_vm = java_vm;
    ctx->sdk_ver = sdk_ver;
    ctx->running = 0;
    ctx->zdt = NULL;
    ctx->jni_env = NULL;
    ctx->on_packet_mid = NULL;
    ctx->protect_socket_mid = NULL;
    ctx->on_stats_mid = NULL;
    ctx->bytes_sent = 0;
    ctx->bytes_received = 0;
    ctx->num_connections = 0;
    ctx->last_stats_time = 0;

    JNIEnv *env = NULL;
    (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6);
    ctx->capture_service = (*env)->NewGlobalRef(env, capture_service);

    return ctx;
}

void capture_ctx_destroy(capture_ctx_t *ctx) {
    if (!ctx) return;

    if (ctx->capture_service) {
        JNIEnv *env = NULL;
        (*ctx->java_vm)->GetEnv(ctx->java_vm, (void **)&env, JNI_VERSION_1_6);
        if (env) {
            (*env)->DeleteGlobalRef(env, ctx->capture_service);
        }
    }

    free(ctx);
}
