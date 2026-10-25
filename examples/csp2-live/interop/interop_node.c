#include <arpa/inet.h>
#include <netdb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <csp/csp.h>
#include <csp/interfaces/csp_if_udp.h>

#include "csp_posix_helper.h"

#define NODE_ADDRESS 228
#define YAMCS_ADDRESS 1
#define TELEMETRY_PORT 10
#define COMMAND_PORT 10

static const uint8_t payload[] = {1, 2, 3};

static const char *resolve_ipv4(const char *hostname, char *buffer, size_t size) {
    struct addrinfo hints = {0};
    struct addrinfo *result = NULL;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;

    if (getaddrinfo(hostname, NULL, &hints, &result) != 0 || result == NULL) {
        return NULL;
    }
    struct sockaddr_in *address = (struct sockaddr_in *)result->ai_addr;
    const char *resolved = inet_ntop(AF_INET, &address->sin_addr, buffer, size);
    freeaddrinfo(result);
    return resolved;
}

static void fail(const char *message) {
    fprintf(stderr, "INTEROP_FAILED: %s\n", message);
    exit(EXIT_FAILURE);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: interop-node <yamcs-host> <local-udp-port>\n");
        return EXIT_FAILURE;
    }

    char peer_address[INET_ADDRSTRLEN];
    if (resolve_ipv4(argv[1], peer_address, sizeof(peer_address)) == NULL) {
        fail("cannot resolve Yamcs host");
    }

    char *end = NULL;
    long local_port = strtol(argv[2], &end, 10);
    if (*argv[2] == '\0' || *end != '\0' || local_port < 1 || local_port > 65535) {
        fail("invalid local UDP port");
    }

    csp_conf.version = 2;
    csp_init();
    if (router_start() != CSP_ERR_NONE) {
        fail("can't start libcsp router");
    }

    csp_iface_t *iface = calloc(1, sizeof(*iface));
    if (iface == NULL) {
        fail("can't allocate UDP interface");
    }
    static csp_if_udp_conf_t udp_config;
    udp_config.host = peer_address;
    udp_config.lport = (uint16_t)local_port;
    udp_config.rport = 11015;
    csp_if_udp_init(iface, &udp_config);
    iface->addr = NODE_ADDRESS;
    // Minimal node has no routing table; all outbound CSP traffic uses this UDP peer
    iface->is_default = 1;

    csp_socket_t socket = {0};
    csp_bind(&socket, CSP_ANY);
    csp_listen(&socket, 4);

    // Give router thread time to start before submitting first packet
    usleep(500000);
    csp_conn_t *telemetry_connection = csp_connect(
            CSP_PRIO_NORM, YAMCS_ADDRESS, TELEMETRY_PORT, 1000, CSP_O_CRC32);
    if (telemetry_connection == NULL) {
        fail("can't create telemetry connection");
    }
    csp_packet_t *telemetry = csp_buffer_get(0);
    if (telemetry == NULL) {
        fail("can't allocate telemetry packet");
    }
    memcpy(telemetry->data, payload, sizeof(payload));
    telemetry->length = sizeof(payload);
    csp_send(telemetry_connection, telemetry);
    csp_close(telemetry_connection);

#ifdef CSP_21
    const char *crc_mode = "header_data";
#else
    const char *crc_mode = "data";
#endif
    printf("TELEMETRY_SENT crc_mode=%s destination=%u source=%u payload=010203\n",
            crc_mode, YAMCS_ADDRESS, NODE_ADDRESS);
    fflush(stdout);

    csp_conn_t *command_connection = csp_accept(&socket, 15000);
    if (command_connection == NULL) {
        csp_iflist_print();
        fail("timed out waiting for Yamcs command");
    }
    csp_packet_t *command = csp_read(command_connection, 1000);
    if (command == NULL) {
        fail("command connection contained no packet");
    }
    if (csp_conn_dport(command_connection) != COMMAND_PORT) {
        fail("command arrived on unexpected CSP port");
    }
    if ((command->id.flags & CSP_FCRC32) == 0) {
        fail("command didn't carry CRC32 flag");
    }
    if (command->length != sizeof(payload) || memcmp(command->data, payload, sizeof(payload)) != 0) {
        fail("command payload didn't match 010203");
    }

    printf("COMMAND_RECEIVED crc_mode=%s destination=%u source=%u payload=010203\n",
            crc_mode, command->id.dst, command->id.src);
    fflush(stdout);

    csp_buffer_free(command);
    csp_close(command_connection);
    return EXIT_SUCCESS;
}
