/* CI-only guest BSD socket experiment. Loopback only; no user content. */
#include <switch.h>
#include <arpa/inet.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static void record(const char* format, ...) {
    char buffer[256]; va_list args; va_start(args, format);
    int n = vsnprintf(buffer, sizeof(buffer), format, args); va_end(args);
    if (n > 0) svcOutputDebugString(buffer, (size_t)n < sizeof(buffer) ? n : sizeof(buffer) - 1);
}
int main(void) {
    int fd = -1, bound = -1;
    FILE* f = fopen("sdmc:/ci-net-mode.txt", "r");
    if (f) { char text[16] = {0}; if (fgets(text, sizeof(text), f)) {
        if (strcmp(text, "bound\n") == 0) bound = 1;
        else if (strcmp(text, "auto\n") == 0) bound = 0;
    } fclose(f); }
    record("GUEST_NET_BEGIN bind=%d", bound);
    if (bound < 0) { record("GUEST_NET_ERROR stage=mode"); goto done; }
    Result rc = socketInitializeDefault();
    record("GUEST_NET_SOCKET_INIT rc=0x%08x", rc);
    if (R_FAILED(rc)) { record("GUEST_NET_ERROR stage=socket_init rc=0x%08x", rc); goto done; }
    fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) { record("GUEST_NET_ERROR stage=socket errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto socket_done; }
    if (bound) {
        struct sockaddr_in local = {0};
        local.sin_family = AF_INET; local.sin_addr.s_addr = htonl(0x7f000001);
        if (bind(fd, (struct sockaddr*)&local, sizeof(local)) < 0) {
            record("GUEST_NET_ERROR stage=bind errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto socket_done;
        }
        record("GUEST_NET_LOOPBACK_BOUND");
    }
    struct sockaddr_in remote = {0};
    remote.sin_family = AF_INET; remote.sin_port = htons(19444); remote.sin_addr.s_addr = htonl(0x7f000001);
    record("GUEST_NET_CONNECT_BEGIN bind=%d", bound);
    if (connect(fd, (struct sockaddr*)&remote, sizeof(remote)) < 0) {
        record("GUEST_NET_ERROR stage=connect errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto socket_done;
    }
    record("GUEST_NET_CONNECTED bind=%d", bound);
    if (send(fd, "net-ping", 8, 0) != 8) { record("GUEST_NET_ERROR stage=send errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto socket_done; }
    char response[8]; size_t total = 0;
    while (total < sizeof(response)) {
        ssize_t n = recv(fd, response + total, sizeof(response) - total, 0);
        if (n <= 0) { record("GUEST_NET_ERROR stage=recv errno=%d bsd=0x%08x", errno, socketGetLastResult()); goto socket_done; }
        total += (size_t)n;
    }
    if (memcmp(response, "net-pong", 8) != 0) { record("GUEST_NET_ERROR stage=payload"); goto socket_done; }
    record("GUEST_NET_EXCHANGE_OK bind=%d", bound);
socket_done:
    if (fd >= 0) close(fd);
    socketExit();
done:
    record("GUEST_NET_DONE");
    svcSleepThread(15000000000LL);
    return 0;
}
