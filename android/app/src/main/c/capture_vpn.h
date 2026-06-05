#ifndef CAPTURE_VPN_H
#define CAPTURE_VPN_H

#include <stdint.h>
#include <jni.h>
#include "zdtun/zdtun.h"

typedef struct {
    JavaVM *java_vm;
    jobject capture_service;
    jint sdk_ver;
    int tunfd;
    zdtun_t *zdt;
    volatile int running;
    JNIEnv *jni_env;
    jmethodID on_packet_mid;
    jmethodID protect_socket_mid;
    jmethodID on_stats_mid;
    zdtun_ip_t real_dns_ip4;
    zdtun_ip_t real_dns_ip6;
    uint8_t real_dns_ipver;
    uint16_t real_dns_port;
    zdtun_ip_t virtual_dns_ip4;
    zdtun_ip_t virtual_dns_ip6;
    uint64_t bytes_sent;
    uint64_t bytes_received;
    uint32_t num_connections;
    uint64_t last_stats_time;
} capture_ctx_t;

capture_ctx_t *capture_ctx_create(JavaVM *java_vm, jobject capture_service, jint sdk_ver);
void capture_ctx_destroy(capture_ctx_t *ctx);
int run_vpn_loop(capture_ctx_t *ctx);
int cache_jni_methods(capture_ctx_t *ctx);
void capture_set_dns_server(capture_ctx_t *ctx, const char *dns_ip, uint16_t dns_port, uint8_t ipver);

#endif
