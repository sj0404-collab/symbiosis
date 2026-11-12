/* CI-only simultaneous TLS attempts in distinct guest contexts, local server. */
#include <switch.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <stdatomic.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void record(const char *fmt, ...) {
    char b[256]; va_list a; va_start(a, fmt); int n = vsnprintf(b, sizeof(b), fmt, a); va_end(a);
    if (n > 0) svcOutputDebugString(b, (size_t)n < sizeof(b) ? (size_t)n : sizeof(b)-1);
}
static atomic_int ready = 0, started = 0;
typedef struct { const char *role; SslContext *ctx; int expect_ok; int ok; } Case;
static int barrier(atomic_int *value) {
    atomic_fetch_add(value, 1);
    for (int i=0; i<600; ++i) {
        if (atomic_load(value) == 2) return 1;
        svcSleepThread(50000000LL);
    }
    return 0;
}
static void run_case(void *arg) {
    Case *c = arg;
    SslConnection conn = {0}; int has_conn = 0, fd = -1, transferred = -1;
    Result rc = sslContextCreateConnection(c->ctx, &conn);
    if (R_FAILED(rc)) goto fail;
    has_conn = 1;
    rc = sslConnectionSetVerifyOption(&conn, SslVerifyOption_PeerCa | SslVerifyOption_HostName);
    if (R_FAILED(rc)) goto fail;
    u32 flags = 0;
    rc = sslConnectionGetVerifyOption(&conn, &flags);
    if (R_FAILED(rc) || (flags & (SslVerifyOption_PeerCa | SslVerifyOption_HostName)) != (SslVerifyOption_PeerCa | SslVerifyOption_HostName)) goto fail;
    rc = sslConnectionSetHostName(&conn, "localhost", sizeof("localhost"));
    if (R_FAILED(rc)) goto fail;
    fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) goto fail;
    struct sockaddr_in addr = {0}; addr.sin_family = AF_INET; addr.sin_port = htons(19445); addr.sin_addr.s_addr = htonl(0x7f000001);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) goto fail;
    errno = 0;
    transferred = socketSslConnectionSetSocketDescriptor(&conn, fd);
    if (transferred < 0 && errno != ENOENT) goto fail;
    record("TLS_PARALLEL_READY role=%s", c->role);
    if (!barrier(&ready)) goto fail;
    record("TLS_PARALLEL_BEGIN role=%s", c->role);
    if (!barrier(&started)) goto fail;
    rc = sslConnectionDoHandshake(&conn, NULL, NULL, NULL, 0);
    if (!c->expect_ok) {
        if (R_SUCCEEDED(rc)) goto fail;
        char dummy[8]; u32 count = 0;
        Result rd = sslConnectionRead(&conn, dummy, sizeof(dummy), &count);
        Result wr = sslConnectionWrite(&conn, "x", 1, &count);
        if (R_SUCCEEDED(rd) || R_SUCCEEDED(wr)) goto fail;
        record("TLS_PARALLEL_REJECTED role=%s rc=0x%08x rd=0x%08x wr=0x%08x", c->role, rc, rd, wr);
    } else {
        if (R_FAILED(rc)) goto fail;
        u32 wrote = 0, read = 0; char answer[16] = {0};
        rc = sslConnectionWrite(&conn, "guest-ping", 10, &wrote);
        if (R_FAILED(rc) || wrote != 10) goto fail;
        for (int i=0; i<60; ++i) {
            rc = sslConnectionRead(&conn, answer, sizeof(answer), &read);
            if (rc != MAKERESULT(123, 204)) break;
            svcSleepThread(50000000LL);
        }
        if (R_FAILED(rc) || read != 10 || memcmp(answer, "guest-pong", 10)) goto fail;
        record("TLS_PARALLEL_EXCHANGE role=%s", c->role);
    }
    c->ok = 1;
    goto done;
fail:
    record("TLS_PARALLEL_ERROR role=%s rc=0x%08x errno=%d", c->role, rc, errno);
done:
    if (transferred >= 0 && transferred != fd) close(transferred);
    if (has_conn) sslConnectionClose(&conn);
    if (fd >= 0) close(fd);
}
static int read_cert(const char *path, unsigned char *buf, size_t cap, size_t *size) {
    FILE *f = fopen(path, "rb"); if (!f) return 0;
    *size = fread(buf, 1, cap, f); int err = ferror(f); fclose(f);
    return !err && *size && *size < cap;
}
int main(void) {
    record("TLS_PARALLEL_TEST_BEGIN");
    if (R_FAILED(socketInitializeDefault())) { record("TLS_PARALLEL_ERROR role=setup_socket"); goto end; }
    if (R_FAILED(sslInitialize(1))) { record("TLS_PARALLEL_ERROR role=setup_ssl"); socketExit(); goto end; }
    SslContext good = {0}, bad = {0};
    int good_open = 0, bad_open = 0; Thread threads[2] = {0}; int created = 0, launched = 0;
    unsigned char cert[4096], wrong[4096]; size_t len = 0, wrong_len = 0; u64 id = 0;
    if (!read_cert("sdmc:/ci-test-cert.pem", cert, sizeof(cert), &len) || !read_cert("sdmc:/ci-wrong-ca.pem", wrong, sizeof(wrong), &wrong_len)) goto setup_fail;
    if (R_FAILED(sslCreateContext(&good, SslVersion_Auto))) goto setup_fail;
    good_open = 1;
    if (R_FAILED(sslCreateContext(&bad, SslVersion_Auto))) goto setup_fail;
    bad_open = 1;
    if (R_FAILED(sslContextImportServerPki(&good, cert, (u32)len, SslCertificateFormat_Pem, &id)) ||
        R_FAILED(sslContextImportServerPki(&bad, wrong, (u32)wrong_len, SslCertificateFormat_Pem, &id))) goto setup_fail;
    Case cases[] = {{"trusted", &good, 1, 0}, {"wrong_ca", &bad, 0, 0}};
    for (int i=0; i<2; i++) {
        if (R_FAILED(threadCreate(&threads[i], run_case, &cases[i], NULL, 0x40000, 0x2b, -2))) goto thread_done;
        created++;
    }
    for (int i=0; i<2; i++) {
        if (R_FAILED(threadStart(&threads[i]))) goto thread_done;
        launched++;
    }
thread_done:
    for (int i=0; i<launched; i++) threadWaitForExit(&threads[i]);
    for (int i=0; i<created; i++) threadClose(&threads[i]);
    if (launched == 2 && cases[0].ok && cases[1].ok) record("TLS_PARALLEL_COMPLETE");
    else record("TLS_PARALLEL_ERROR role=threads");
    goto cleanup;
setup_fail:
    record("TLS_PARALLEL_ERROR role=setup");
cleanup:
    if (bad_open) sslContextClose(&bad);
    if (good_open) sslContextClose(&good);
    sslExit(); socketExit();
end:
    svcSleepThread(15000000000LL);
    return 0;
}
