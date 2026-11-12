/* Disposable diagnostic NRO: real bsd + ssl service, local test server only.
 * No TLS bypass, commercial content, user keys, or release APK inclusion. */
#include <switch.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
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

/* Optional source-only count test; omitted in ordinary pinned-core tests. */
static int check_count(SslContext *ctx, const char *label, const char *context_name, u32 expected) {
    u32 count = UINT32_MAX;
    Result rc = sslContextGetConnectionCount(ctx, &count);
    say("GUEST_SSL_COUNT phase=%s context=%s count=%u rc=0x%08x", label, context_name, count, rc);
    if (R_FAILED(rc) || count != expected) {
        say("GUEST_TLS_ERROR stage=connection_count phase=%s context=%s expected=%u actual=%u rc=0x%08x", label, context_name, expected, count, rc);
        return 0;
    }
    return 1;
}
static int count_test_enabled(void) {
    FILE *f = fopen("sdmc:/ci-ssl-count.txt", "r");
    if (!f) return 0;
    char mode[16] = {0};
    int enabled = fgets(mode, sizeof(mode), f) && strcmp(mode, "enabled\n") == 0;
    fclose(f);
    return enabled ? 1 : -1;
}
static int check_count_lifecycle(SslContext *a, SslContext *b) {
    SslConnection a1 = {0}, a2 = {0}, b1 = {0};
    int a1_open = 0, a2_open = 0, b1_open = 0, ok = 0;
    if (!check_count(a, "initial", "A", 0) || !check_count(b, "initial", "B", 0)) goto done;
    if (!check("count_create_a1", sslContextCreateConnection(a, &a1))) goto done;
    a1_open = 1;
    if (!check_count(a, "one", "A", 1)) goto done;
    if (!check("count_create_a2", sslContextCreateConnection(a, &a2))) goto done;
    a2_open = 1;
    if (!check_count(a, "two", "A", 2)) goto done;
    if (!check("count_create_b1", sslContextCreateConnection(b, &b1))) goto done;
    b1_open = 1;
    if (!check_count(b, "other_created", "B", 1) || !check_count(a, "other_created", "A", 2)) goto done;
    sslConnectionClose(&a1); a1_open = 0;
    if (!check_count(a, "one_closed", "A", 1) || !check_count(b, "one_closed", "B", 1)) goto done;
    sslConnectionClose(&b1); b1_open = 0;
    if (!check_count(b, "other_closed", "B", 0) || !check_count(a, "other_closed", "A", 1)) goto done;
    sslConnectionClose(&a2); a2_open = 0;
    if (!check_count(a, "all_closed", "A", 0) || !check_count(b, "all_closed", "B", 0)) goto done;
    say("GUEST_SSL_COUNT_LIFECYCLE_OK");
    ok = 1;
done:
    if (a1_open) sslConnectionClose(&a1);
    if (a2_open) sslConnectionClose(&a2);
    if (b1_open) sslConnectionClose(&b1);
    return ok;
}

/* The mode is configured on the disposable SD card; never assume it. */
static int use_auto_bind = -1;
/* Require genuine verify flags and the same local server in both trials. */
static int connection(SslContext* ctx, const char* host, const char* negative) {
    SslConnection conn = {0};
    int fd = -1, transferred = -1, status = 0;
    Result rc;
    if (!check("connection_create", sslContextCreateConnection(ctx, &conn))) return 0;
    Result skip = sslConnectionSetOption(&conn, SslOptionType_SkipDefaultVerify, true);
    Result alpn = sslConnectionSetOption(&conn, SslOptionType_EnableAlpn, true);
    Result chain = sslConnectionSetOption(&conn, SslOptionType_GetServerCertChain, true);
    if (R_SUCCEEDED(skip) || R_SUCCEEDED(alpn) || R_SUCCEEDED(chain)) {
        say("GUEST_TLS_ERROR stage=unsupported_option_accepted skip=0x%08x alpn=0x%08x chain=0x%08x", skip, alpn, chain);
        goto done;
    }
    say("GUEST_TLS_UNSUPPORTED_OPTIONS_REJECTED skip=0x%08x alpn=0x%08x chain=0x%08x", skip, alpn, chain);
    rc = sslConnectionSetVerifyOption(&conn, 0);
    if (R_SUCCEEDED(rc)) { say("GUEST_TLS_ERROR stage=verification_disabled"); goto done; }
    say("GUEST_TLS_VERIFY_DISABLE_REJECTED rc=0x%08x", rc);
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
    if (!use_auto_bind) {
        // This mode isolates TLS from the automatic interface-selection path.
        struct sockaddr_in local = {0};
        local.sin_family = AF_INET; local.sin_addr.s_addr = htonl(0x7f000001);
        if (bind(fd, (struct sockaddr*)&local, sizeof(local)) < 0) {
            say("GUEST_TLS_ERROR stage=loopback_bind errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto done;
        }
        say("GUEST_TLS_LOOPBACK_BOUND");
    }
    say("GUEST_TLS_CONNECT_BEGIN mode=%s", use_auto_bind ? "auto" : "bound");
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
    if (negative) {
        if (R_FAILED(rc)) {
            say("GUEST_TLS_NEGATIVE_HANDSHAKE_FAILED case=%s rc=0x%08x", negative, rc);
            // A rejected handshake must not leave I/O reporting success or
            // kill the guest process. These are not application data tests.
            char dummy[8]; u32 count = 0;
            Result rd = sslConnectionRead(&conn, dummy, sizeof(dummy), &count);
            Result wr = sslConnectionWrite(&conn, "x", 1, &count);
            if (R_SUCCEEDED(rd) || R_SUCCEEDED(wr)) {
                say("GUEST_TLS_ERROR stage=io_after_failed_handshake case=%s rd=0x%08x wr=0x%08x", negative, rd, wr);
                goto done;
            }
            say("GUEST_TLS_FAILED_HANDSHAKE_IO_REJECTED case=%s rd=0x%08x wr=0x%08x", negative, rd, wr);
            Result error = sslConnectionGetVerifyCertError(&conn);
            u32 out0 = 0, out1 = 0; Result errors[4] = {0};
            Result errors_rc = sslConnectionGetVerifyCertErrors(&conn, &out0, &out1, errors, 4);
            if (R_SUCCEEDED(error) || R_SUCCEEDED(errors_rc)) {
                say("GUEST_TLS_ERROR stage=unsupported_error_detail_succeeded case=%s", negative);
                goto done;
            }
            say("GUEST_TLS_VERIFY_DETAILS_UNSUPPORTED case=%s single=0x%08x list=0x%08x", negative, error, errors_rc);
            status = 1;
        } else say("GUEST_TLS_ERROR stage=negative_accepted case=%s", negative);
        goto done;
    }
    if (!check("handshake", rc)) goto done;
    const char request[] = "guest-ping";
    u32 wrote = 0, read = 0;
    if (!check("write", sslConnectionWrite(&conn, request, sizeof(request)-1, &wrote)) || wrote != sizeof(request)-1) goto done;
    char answer[32] = {0};
    // WouldBlock may be returned before the server has sent its response.
    for (int attempt = 0; attempt < 60; attempt++) {
        rc = sslConnectionRead(&conn, answer, sizeof(answer), &read);
        if (rc != MAKERESULT(123, 204)) break;
        svcSleepThread(50000000LL);
    }
    if (!check("read", rc)) goto done;
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
static int read_cert(const char* path, unsigned char* out, size_t capacity, size_t* length) {
    FILE* f = fopen(path, "rb");
    if (!f) { say("GUEST_TLS_ERROR stage=guest_cert_open path=%s errno=%d", path, errno); return 0; }
    *length = fread(out, 1, capacity, f);
    int failed = ferror(f); fclose(f);
    if (failed || !*length || *length == capacity) { say("GUEST_TLS_ERROR stage=guest_cert_size path=%s", path); return 0; }
    return 1;
}
int main(void) {
    say("GUEST_TLS_BEGIN");
    FILE *mode_file = fopen("sdmc:/ci-tls-mode.txt", "r");
    if (mode_file) {
        char mode[16] = {0};
        if (fgets(mode, sizeof(mode), mode_file)) {
            if (strcmp(mode, "auto\n") == 0) use_auto_bind = 1;
            else if (strcmp(mode, "bound\n") == 0) use_auto_bind = 0;
        }
        fclose(mode_file);
    }
    if (use_auto_bind < 0) { say("GUEST_TLS_ERROR stage=mode"); goto finish; }
    say("GUEST_TLS_MODE mode=%s", use_auto_bind ? "auto" : "bound");
    if (!check("socket_init", socketInitializeDefault())) goto finish;
    if (!check("ssl_init", sslInitialize(1))) { socketExit(); goto finish; }
    SslContext ctx = {0}, other = {0};
    int other_open = 0;
    if (!check("context", sslCreateContext(&ctx, SslVersion_Auto))) goto ssl_done;
    if (!check("other_context", sslCreateContext(&other, SslVersion_Auto))) goto ctx_done;
    other_open = 1;
    int count_mode = count_test_enabled();
    if (count_mode < 0) { say("GUEST_TLS_ERROR stage=count_mode"); goto ctx_done; }
    if (count_mode && !check_count_lifecycle(&ctx, &other)) goto ctx_done;
    unsigned char cert[4096], wrong[4096], leaf[4096], der[4096];
    size_t len, wrong_len, leaf_len, der_len;
    if (!read_cert("sdmc:/ci-test-cert.pem", cert, sizeof(cert), &len) ||
        !read_cert("sdmc:/ci-wrong-ca.pem", wrong, sizeof(wrong), &wrong_len) ||
        !read_cert("sdmc:/ci-leaf.pem", leaf, sizeof(leaf), &leaf_len) ||
        !read_cert("sdmc:/ci-test-cert.der", der, sizeof(der), &der_len)) goto ctx_done;
    u64 id = 0, wrong_id = 0;
    const char invalid_cert[] = "not a certificate";
    Result invalid = sslContextImportServerPki(&ctx, invalid_cert, sizeof(invalid_cert)-1, SslCertificateFormat_Pem, &id);
    if (R_SUCCEEDED(invalid)) { say("GUEST_TLS_ERROR stage=invalid_certificate_accepted"); goto ctx_done; }
    say("GUEST_TLS_INVALID_CERT_REJECTED rc=0x%08x", invalid);
    Result client_rc = sslContextImportClientPki(&ctx, invalid_cert, sizeof(invalid_cert)-1, "", 1, &id);
    if (R_SUCCEEDED(client_rc)) { say("GUEST_TLS_ERROR stage=unsupported_client_pki_succeeded"); goto ctx_done; }
    say("GUEST_TLS_CLIENT_PKI_REJECTED rc=0x%08x", client_rc);
    Result leaf_rc = sslContextImportServerPki(&ctx, leaf, (u32)leaf_len, SslCertificateFormat_Pem, &id);
    if (R_SUCCEEDED(leaf_rc)) { say("GUEST_TLS_ERROR stage=leaf_as_ca_accepted"); goto ctx_done; }
    say("GUEST_TLS_LEAF_AS_CA_REJECTED rc=0x%08x", leaf_rc);
    if (!connection(&ctx, "localhost", "no_import")) goto ctx_done;
    if (!check("import_wrong_ca", sslContextImportServerPki(&ctx, wrong, (u32)wrong_len, SslCertificateFormat_Pem, &wrong_id))) goto ctx_done;
    if (!connection(&ctx, "localhost", "wrong_ca")) goto ctx_done;
    if (!check("remove_wrong_ca", sslContextRemovePki(&ctx, wrong_id))) goto ctx_done;
    if (!check("import_test_ca", sslContextImportServerPki(&ctx, cert, (u32)len, SslCertificateFormat_Pem, &id))) goto ctx_done;
    say("GUEST_TLS_CA_IMPORT_RETURNED bytes=%zu id=%llu", len, (unsigned long long)id);
    if (!connection(&ctx, "localhost", NULL)) goto ctx_done;
    if (!connection(&other, "localhost", "other_context")) goto ctx_done;
    if (!connection(&ctx, "wrong.invalid", "wrong_host")) goto ctx_done;
    if (!check("remove_test_ca", sslContextRemovePki(&ctx, id))) goto ctx_done;
    // libnx's convenience wrapper probes server, client and CRL removal on
    // a missing ID. Test command 6 directly so an unsupported *different*
    // command does not hide whether server removal correctly reports missing.
    Result second_remove = serviceDispatchIn(&ctx.s, 6, id);
    if (R_SUCCEEDED(second_remove)) { say("GUEST_TLS_ERROR stage=removed_id_still_exists"); goto ctx_done; }
    say("GUEST_TLS_REMOVED_ID_REJECTED rc=0x%08x", second_remove);
    // Also exercise libnx's server/client/CRL fallback; unsupported client PKI
    // must return an error rather than crash or pretend a successful removal.
    Result fallback = sslContextRemovePki(&ctx, id);
    if (R_SUCCEEDED(fallback)) { say("GUEST_TLS_ERROR stage=unsupported_pki_removal_succeeded"); goto ctx_done; }
    say("GUEST_TLS_REMOVE_FALLBACK_REJECTED rc=0x%08x", fallback);
    if (!connection(&ctx, "localhost", "untrusted_after_remove")) goto ctx_done;
    if (!check("import_der_ca", sslContextImportServerPki(&ctx, der, (u32)der_len, SslCertificateFormat_Der, &id))) goto ctx_done;
    say("GUEST_TLS_DER_CA_IMPORT_RETURNED");
    if (!connection(&ctx, "localhost", NULL)) goto ctx_done;
    // Server presents leaf certificates signed by the same imported root.
    say("GUEST_TLS_CHAIN_CASE valid_server");
    if (!connection(&ctx, "localhost", NULL)) goto ctx_done;
    say("GUEST_TLS_CHAIN_CASE expired_server");
    if (!connection(&ctx, "localhost", "expired_server")) goto ctx_done;
    say("GUEST_TLS_CHAIN_CASE client_eku_only");
    if (!connection(&ctx, "localhost", "client_eku_only")) goto ctx_done;
    say("GUEST_TLS_CHAIN_CASE intermediate_chain");
    if (!connection(&ctx, "localhost", NULL)) goto ctx_done;
    say("GUEST_TLS_CHAIN_CASE missing_intermediate");
    if (!connection(&ctx, "localhost", "missing_intermediate")) goto ctx_done;
    say("GUEST_TLS_CHAIN_CASE not_yet_valid");
    if (!connection(&ctx, "localhost", "not_yet_valid")) goto ctx_done;
    if (count_mode && (!check_count(&ctx, "after_tls", "A", 0) || !check_count(&other, "after_tls", "B", 0))) goto ctx_done;
    if (count_mode) say("GUEST_SSL_COUNT_AFTER_TLS_OK");
    say("GUEST_TLS_COMPLETE");
ctx_done:
    if (other_open) sslContextClose(&other);
    sslContextClose(&ctx);
ssl_done:
    sslExit(); socketExit();
finish:
    svcSleepThread(15000000000LL);
    return 0;
}
