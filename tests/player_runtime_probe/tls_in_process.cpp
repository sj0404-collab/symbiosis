// Compiled ONLY into a disposable debug build, never the release APK.
// Perform real TLS inside the player process against its already-loaded libs.
#include <android/log.h>
#include <string>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>

int tls_handshake_probe(int, char**, long*);
extern "C" __attribute__((visibility("default")))
int ci_tls_in_process(const char* libdir, const char* certdir) {
    const std::string cert = std::string(certdir) + "/ci-test-cert.pem";
    const std::string key = std::string(certdir) + "/ci-test-key.pem";
    int certFd = open(cert.c_str(), O_RDONLY);
    int certErr = certFd < 0 ? errno : 0;
    if (certFd >= 0) close(certFd);
    int keyFd = open(key.c_str(), O_RDONLY);
    int keyErr = keyFd < 0 ? errno : 0;
    if (keyFd >= 0) close(keyFd);
    __android_log_print(ANDROID_LOG_INFO, "SymbiosisKenji",
                        "CI_ONLY_TLS_TEST_FILES cert_errno=%d key_errno=%d dir=%s",
                        certErr, keyErr, certdir);
    char host_good[] = "localhost", host_bad[] = "wrong.invalid", name[] = "tls-probe";
    char* argv[] = {name, const_cast<char*>(libdir), const_cast<char*>(cert.c_str()),
                    const_cast<char*>(key.c_str()), host_good};
    long verified = -1;
    const int good = tls_handshake_probe(5, argv, &verified);
    const long goodVerify = verified;
    argv[4] = host_bad;
    const int bad = tls_handshake_probe(5, argv, &verified);
    // X509_V_ERR_HOSTNAME_MISMATCH = 62. Rejecting for a missing certificate or
    // an unrelated error is not a successful hostname test.
    const bool ok = good == 0 && goodVerify == 0 && bad == 5 && verified == 62;
    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "SymbiosisKenji",
                        "CI_ONLY_TLS_IN_PROCESS good=%d verify=%ld wrong_host=%d wrong_verify=%ld",
                        good, goodVerify, bad, verified);
    return ok ? 0 : 1;
}
