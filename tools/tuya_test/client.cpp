// Клієнт з прошивки проти імітації розетки (fake_plug.py) по справжньому TCP.
// client <port> <ver> <key> <query|on|off>
#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <ctime>
#include "tuya.h"

struct Sock : tuya::Transport {
  int fd;
  bool write(const uint8_t* d, size_t n) override { return ::send(fd, d, n, 0) == (ssize_t)n; }
  int read(uint8_t* d, size_t n, uint32_t ms) override {
    pollfd p{fd, POLLIN, 0};
    if (poll(&p, 1, ms) <= 0) return 0;
    int r = ::recv(fd, d, n, 0);
    return r <= 0 ? -1 : r;
  }
};

int main(int argc, char** argv) {
  Sock s; s.fd = socket(AF_INET, SOCK_STREAM, 0);
  sockaddr_in a{}; a.sin_family = AF_INET; a.sin_port = htons(atoi(argv[1])); inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
  if (connect(s.fd, (sockaddr*)&a, sizeof a)) { perror("connect"); return 2; }
  int set = !strcmp(argv[4], "on") ? 1 : !strcmp(argv[4], "off") ? 0 : -1;
  std::string err;
  int r = tuya::exchange(s, tuya::parseVer(argv[2]), argv[3], "bf0000000000000000abcd", 1, set, time(nullptr), &err);
  close(s.fd);
  printf("%s -> %d %s\n", argv[4], r, err.c_str());
  return r < 0 ? 1 : (set >= 0 && r != set) ? 1 : 0;
}
