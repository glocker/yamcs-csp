/*
 * Reference vector generator for csp2 module
 *
 * Builds CSP 2.x frames with real libcsp (csp_crc32_append and csp_id_prepend) and prints one CSV line per
 * case. Frame matches one libcsp UDP datagram: 6 header bytes, payload and, when CRC32 flag is set, 4-byte
 * CRC32 trailer.
 *
 * CRC mode (data only, or header and data) is selected when libcsp is built, by CSP_21 macro. Generator detects
 * mode by observing linked library and prints it, so vector label cannot disagree with library that produced it.
 *
 * Output columns:
 *   name,crc_mode,priority,destination,source,destination_port,source_port,flags,payload_hex,frame_hex,expected
 * payload_hex is application payload before CRC is added ("-" if empty). frame_hex is exact frame byte sequence.
 * Payload is ramp 01 02 03 ... unless a case gives explicit bytes. Cases named mdb_* feed MDB example in
 * examples/csp2-live: destination 1 (Yamcs), source 228 (libcsp node) and ports 10, 11, 12 as defined there.
 * expected is libcsp verdict (csp_crc32_verify), or "too_short" for frame shorter than 6-byte header.
 */

#include <csp/csp.h>
#include <csp/csp_crc32.h>
#include <csp/csp_id.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define HEADER_SIZE 6U
#define FRAME_CAPACITY (HEADER_SIZE + CSP_BUFFER_SIZE)
#define TRUNCATED_FRAME_SIZE 5U

enum case_kind {
    KIND_NORMAL,        /* frame exactly as libcsp builds it */
    KIND_CORRUPT_CRC,   /* last CRC byte flipped after the frame was built */
    KIND_NO_TRAILER,    /* CRC32 flag set in the header, but no CRC appended */
    KIND_TRUNCATED      /* frame cut below the header size */
};

struct vector_case {
    const char *name;
    uint8_t priority;
    uint16_t destination;
    uint16_t source;
    uint8_t destination_port;
    uint8_t source_port;
    uint8_t flags;
    uint16_t payload_length;
    enum case_kind kind;
    const uint8_t *payload; /* explicit payload bytes, or NULL for ramp 01 02 03 ... */
};

#define FLAGS_ALL_DEFINED (CSP_FCRC32 | CSP_FRDP | CSP_FHMAC | CSP_FFRAG)

/*
 * Housekeeping payload used by the MDB example (port 11), little-endian like a C struct on a little-endian CPU:
 * uint16 counter, int16 temperature, uint32 uptime, float32 voltage.
 */
static const uint8_t hk_typical[] = {0x02, 0x01, 0x38, 0xFF, 0x40, 0xE2, 0x01, 0x00, 0x00, 0x00, 0x60, 0x40};
/* counter 65535, temperature -32768, uptime 4294967295, voltage -1.5 */
static const uint8_t hk_extreme[] = {0xFF, 0xFF, 0x00, 0x80, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0xC0, 0xBF};

#define HK_LENGTH ((uint16_t)sizeof(hk_typical))

static const struct vector_case cases[] = {
    /* name, priority, destination, source, destination port, source port, flags, payload length, kind, payload */
    {"no_flags", 1, 5, 3, 10, 20, 0, 3, KIND_NORMAL},
    {"no_flags_empty_payload", 1, 5, 3, 10, 20, 0, 0, KIND_NORMAL},
    {"address_min", 0, 0, 0, 0, 0, 0, 1, KIND_NORMAL},
    {"address_max", 3, 16383, 16383, 63, 63, 0, 1, KIND_NORMAL},
    {"priority_0", 0, 2, 1, 4, 5, 0, 2, KIND_NORMAL},
    {"priority_1", 1, 2, 1, 4, 5, 0, 2, KIND_NORMAL},
    {"priority_2", 2, 2, 1, 4, 5, 0, 2, KIND_NORMAL},
    {"priority_3", 3, 2, 1, 4, 5, 0, 2, KIND_NORMAL},
    {"flag_rdp", 1, 5, 3, 10, 20, CSP_FRDP, 3, KIND_NORMAL},
    {"flag_hmac", 1, 5, 3, 10, 20, CSP_FHMAC, 3, KIND_NORMAL},
    {"flag_frag", 1, 5, 3, 10, 20, CSP_FFRAG, 3, KIND_NORMAL},
    {"flags_unknown_bits", 1, 5, 3, 10, 20, 0x24, 3, KIND_NORMAL},
    {"flags_all_defined", 1, 5, 3, 10, 20, FLAGS_ALL_DEFINED, 3, KIND_NORMAL},
    {"flags_all_bits", 1, 5, 3, 10, 20, 0x3F, 3, KIND_NORMAL},
    {"crc", 1, 5, 3, 10, 20, CSP_FCRC32, 3, KIND_NORMAL},
    {"crc_empty_payload", 1, 5, 3, 10, 20, CSP_FCRC32, 0, KIND_NORMAL},
    {"crc_address_max", 3, 16383, 16383, 63, 63, CSP_FCRC32, 3, KIND_NORMAL},
    {"crc_mixed_fields", 2, 16383, 1, 63, 5, CSP_FCRC32, 3, KIND_NORMAL},
    {"payload_max_no_crc", 1, 5, 3, 10, 20, 0, CSP_BUFFER_SIZE, KIND_NORMAL},
    {"payload_max_with_crc", 1, 5, 3, 10, 20, CSP_FCRC32, CSP_BUFFER_SIZE - 4, KIND_NORMAL},
    {"crc_corrupted", 1, 5, 3, 10, 20, CSP_FCRC32, 3, KIND_CORRUPT_CRC},
    {"crc_flag_without_trailer_2", 1, 5, 3, 10, 20, CSP_FCRC32, 2, KIND_NO_TRAILER},
    {"crc_flag_without_trailer_8", 1, 5, 3, 10, 20, CSP_FCRC32, 8, KIND_NO_TRAILER},
    {"truncated_5_bytes", 1, 5, 3, 10, 20, 0, 3, KIND_TRUNCATED},
    /* MDB example: port 10 fixed 3-byte sample, port 11 housekeeping, port 12 raw bytes, other ports header only */
    {"mdb_sample_crc", 2, 1, 228, 10, 7, CSP_FCRC32, 3, KIND_NORMAL},
    {"mdb_hk_crc", 2, 1, 228, 11, 7, CSP_FCRC32, HK_LENGTH, KIND_NORMAL, hk_typical},
    {"mdb_hk_no_crc", 2, 1, 228, 11, 7, 0, HK_LENGTH, KIND_NORMAL, hk_typical},
    {"mdb_hk_crc_extreme", 0, 1, 228, 11, 7, CSP_FCRC32, HK_LENGTH, KIND_NORMAL, hk_extreme},
    {"mdb_hk_crc_corrupted", 2, 1, 228, 11, 7, CSP_FCRC32, HK_LENGTH, KIND_CORRUPT_CRC, hk_typical},
    {"mdb_raw_empty", 1, 1, 228, 12, 7, 0, 0, KIND_NORMAL},
    {"mdb_raw_empty_crc", 1, 1, 228, 12, 7, CSP_FCRC32, 0, KIND_NORMAL},
    {"mdb_raw_1", 1, 1, 228, 12, 7, 0, 1, KIND_NORMAL},
    {"mdb_raw_40_crc", 1, 1, 228, 12, 7, CSP_FCRC32, 40, KIND_NORMAL},
    {"mdb_raw_max", 1, 1, 228, 12, 7, 0, CSP_BUFFER_SIZE, KIND_NORMAL},
    {"mdb_raw_max_crc", 1, 1, 228, 12, 7, CSP_FCRC32, CSP_BUFFER_SIZE - 4, KIND_NORMAL},
    {"mdb_unknown_port", 1, 1, 228, 50, 7, 0, 3, KIND_NORMAL},
    {"mdb_unknown_port_all_flags", 1, 1, 228, 50, 7, FLAGS_ALL_DEFINED, 3, KIND_NORMAL},
};

static void die(const char *message) {
    fprintf(stderr, "vector_generator: %s\n", message);
    exit(EXIT_FAILURE);
}

static uint8_t payload_byte(const struct vector_case *c, uint16_t index) {
    return c->payload != NULL ? c->payload[index] : (uint8_t)(index + 1U);
}

/* Fill zeroed packet with header fields and payload: explicit bytes, or ramp 01 02 03 ... (wrapping at 255). */
static void fill_packet(csp_packet_t *packet, const struct vector_case *c) {
    memset(packet, 0, sizeof(*packet));
    packet->id.pri = c->priority;
    packet->id.dst = c->destination;
    packet->id.src = c->source;
    packet->id.dport = c->destination_port;
    packet->id.sport = c->source_port;
    packet->id.flags = c->flags;
    for (uint16_t i = 0; i < c->payload_length; i++) {
        packet->data[i] = payload_byte(c, i);
    }
    packet->length = c->payload_length;
}

/* Detect libcsp build mode by comparing its CRC with both possible values. */
static const char *detect_crc_mode(void) {
    static const struct vector_case probe = {"probe", 2, 2, 1, 3, 4, CSP_FCRC32, 3, KIND_NORMAL};
    csp_packet_t appended;
    csp_packet_t header_view;

    fill_packet(&appended, &probe);
    fill_packet(&header_view, &probe);

    uint32_t data_crc = csp_crc32_memory(appended.data, appended.length);
    csp_id_prepend(&header_view);
    uint32_t header_and_data_crc = csp_crc32_memory(header_view.frame_begin, header_view.frame_length);

    if (csp_crc32_append(&appended) != CSP_ERR_NONE) {
        die("csp_crc32_append failed on the probe packet");
    }
    const uint8_t *trailer = &appended.data[probe.payload_length];
    uint32_t libcsp_crc = ((uint32_t)trailer[0] << 24) | ((uint32_t)trailer[1] << 16) | ((uint32_t)trailer[2] << 8) |
                          (uint32_t)trailer[3];

    if (libcsp_crc == data_crc) {
        return "data";
    }
    if (libcsp_crc == header_and_data_crc) {
        return "header_data";
    }
    die("libcsp CRC matches neither data only nor header and data checksum");
    return NULL;
}

/*
 * Build frame as libcsp sends it: append CRC first, then prepend header. Order matters because header prepend computes
 * frame length and must see final payload length including CRC trailer.
 */
static size_t build_frame(const struct vector_case *c, uint8_t *frame) {
    csp_packet_t packet;
    fill_packet(&packet, c);

    if ((c->flags & CSP_FCRC32) && c->kind != KIND_NO_TRAILER) {
        if (csp_crc32_append(&packet) != CSP_ERR_NONE) {
            die("csp_crc32_append failed: payload doesn't fit into the packet buffer");
        }
    }
    csp_id_prepend(&packet);

    size_t length = packet.frame_length;
    if (length > FRAME_CAPACITY) {
        die("frame is larger than frame buffer");
    }
    memcpy(frame, packet.frame_begin, length);

    if (c->kind == KIND_CORRUPT_CRC) {
        frame[length - 1] ^= 0x01U;
    }
    if (c->kind == KIND_TRUNCATED) {
        length = TRUNCATED_FRAME_SIZE;
    }
    return length;
}

/* Let libcsp judge printed bytes as receiver would after stripping header. */
static const char *expected_result(const struct vector_case *c, const uint8_t *frame, size_t length) {
    if (c->kind == KIND_TRUNCATED) {
        return "too_short";
    }
    if (!(c->flags & CSP_FCRC32)) {
        return "valid";
    }

    csp_packet_t received;
    fill_packet(&received, c);
    received.length = (uint16_t)(length - HEADER_SIZE);
    memcpy(received.data, frame + HEADER_SIZE, received.length);
    return csp_crc32_verify(&received) == CSP_ERR_NONE ? "valid" : "crc_error";
}

static void print_hex(const uint8_t *bytes, size_t length) {
    for (size_t i = 0; i < length; i++) {
        printf("%02X", bytes[i]);
    }
}

static void print_payload(const struct vector_case *c) {
    if (c->payload_length == 0) {
        printf("-");
        return;
    }
    for (uint16_t i = 0; i < c->payload_length; i++) {
        printf("%02X", payload_byte(c, i));
    }
}

int main(void) {
    csp_conf.version = 2;

    const char *mode = detect_crc_mode();

    printf("# libcsp reference vectors for the csp2 module (generated by vector_generator.c)\n");
    printf("# crc_mode=%s\n", mode);
    printf("# columns: name,crc_mode,priority,destination,source,destination_port,source_port,flags,payload_hex,"
           "frame_hex,expected\n");

    uint8_t frame[FRAME_CAPACITY];
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        const struct vector_case *c = &cases[i];
        size_t length = build_frame(c, frame);

        printf("%s,%s,%u,%u,%u,%u,%u,%u,", c->name, mode, (unsigned)c->priority, (unsigned)c->destination,
               (unsigned)c->source, (unsigned)c->destination_port, (unsigned)c->source_port, (unsigned)c->flags);
        print_payload(c);
        printf(",");
        print_hex(frame, length);
        printf(",%s\n", expected_result(c, frame, length));
    }
    return EXIT_SUCCESS;
}
