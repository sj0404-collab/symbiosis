/* Disposable diagnostic NRO: real bsd + ssl service, local test server only.
 * No TLS bypass, commercial content, user keys, or release APK inclusion. */
#include <switch.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void say(const char* fmt, ...) {
    char buf[256]; va_list a; va_start(a, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, a); va_end(a);
    if (n > 0) svcOutputDebugString(buf, (size_t)n < sizeof(buf) ? n : sizeof(buf)-1);
}
static int check(const char *stage, Result rc) {
    say("GUEST_TLS_STAGE %s rc=0x%08x module=%u description=%u", stage, rc, R_MODULE(rc), R_DESCRIPTION(rc));
    if (R_FAILED(rc)) { say("GUEST_TLS_ERROR stage=%s rc=0x%08x", stage, rc); return 0; }
    return 1;
}

/* Require genuine verify flags and the same local server in both trials. */
static int connection(SslContext* ctx, const char* host, int correct) {
    SslConnection conn = {0};
    int fd = -1, transferred = -1, status = 0;
    Result rc;
    if (!check("connection_create", sslContextCreateConnection(ctx, &conn))) return 0;
    if (!check("set_verify", sslConnectionSetVerifyOption(&conn, SslVerifyOption_PeerCa | SslVerifyOption_HostName))) goto done;
    u32 flags = 0;
    if (!check("get_verify", sslConnectionGetVerifyOption(&conn, &flags))) goto done;
    say("GUEST_TLS_VERIFY_FLAGS value=0x%x", flags);
    if ((flags & (SslVerifyOption_PeerCa | SslVerifyOption_HostName)) != (SslVerifyOption_PeerCa | SslVerifyOption_HostName)) {
        say("GUEST_TLS_ERROR stage=verify_flags value=0x%x", flags); goto done;
    }
    if (!check("set_hostname", sslConnectionSetHostName(&conn, host, (u32)strlen(host)+1))) goto done;
    fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) { say("GUEST_TLS_ERROR stage=socket errno=%d", errno); goto done; }
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET; addr.sin_port = htons(19443); addr.sin_addr.s_addr = htonl(0x7f000001);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        say("GUEST_TLS_ERROR stage=connect errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto done;
    }
    say("GUEST_TLS_SOCKET_CONNECTED host=%s", host);
    errno = 0;
    transferred = socketSslConnectionSetSocketDescriptor(&conn, fd);
    if (transferred < 0 && errno != ENOENT) {
        say("GUEST_TLS_ERROR stage=set_socket errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto done;
    }
    rc = sslConnectionDoHandshake(&conn, NULL, NULL, NULL, 0);
    say("GUEST_TLS_HANDSHAKE host=%s rc=0x%08x module=%u description=%u", host, rc, R_MODULE(rc), R_DESCRIPTION(rc));
    if (!correct) {
        if (R_FAILED(rc)) { say("GUEST_TLS_WRONG_HOST_HANDSHAKE_FAILED rc=0x%08x", rc); status = 1; }
        else say("GUEST_TLS_ERROR stage=wrong_hostname_accepted");
        goto done;
    }
    if (!check("handshake", rc)) goto done;
    const char request[] = "guest-ping";
    u32 wrote = 0, read = 0;
    if (!check("write", sslConnectionWrite(&conn, request, sizeof(request)-1, &wrote)) || wrote != sizeof(request)-1) goto done;
    char answer[32] = {0};
    if (!check("read", sslConnectionRead(&conn, answer, sizeof(answer), &read))) goto done;
    if (read != 10 || memcmp(answer, "guest-pong", 10) != 0) {
        say("GUEST_TLS_ERROR stage=payload size=%u", read); goto done;
    }
    say("GUEST_TLS_VERIFIED_EXCHANGE_OK"); status = 1;
done:
    if (transferred >= 0 && transferred != fd) close(transferred);
    sslConnectionClose(&conn);
    if (fd >= 0) close(fd);
    return status;
}
int main(void) {
    say("GUEST_TLS_BEGIN");
    if (!check("socket_init", socketInitializeDefault())) goto finish;
    if (!check("ssl_init", sslInitialize(1))) { socketExit(); goto finish; }
    SslContext ctx = {0};
    if (!check("context", sslCreateContext(&ctx, SslVersion_Auto))) goto ssl_done;
    FILE* f = fopen("sdmc:/ci-test-cert.pem", "rb");
    if (!f) { say("GUEST_TLS_ERROR stage=guest_cert_open errno=%d", errno); goto ctx_done; }
    unsigned char cert[4096]; size_t len = fread(cert, 1, sizeof(cert), f); fclose(f);
    if (!len || len == sizeof(cert)) { say("GUEST_TLS_ERROR stage=guest_cert_size bytes=%zu", len); goto ctx_done; }
    u64 id = 0;
    if (!check("import_test_ca", sslContextImportServerPki(&ctx, cert, (u32)len, SslCertificateFormat_Pem, &id))) goto ctx_done;
    say("GUEST_TLS_CA_IMPORTED bytes=%zu id=%llu", len, (unsigned long long)id);
    if (!connection(&ctx, "localhost", 1)) goto ctx_done;
    if (!connection(&ctx, "wrong.invalid", 0)) goto ctx_done;
    say("GUEST_TLS_COMPLETE");
ctx_done:
    sslContextClose(&ctx);
ssl_done:
    sslExit(); socketExit();
finish:
    svcSleepThread(15000000000LL);
    return 0;
}
