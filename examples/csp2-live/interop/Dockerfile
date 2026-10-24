FROM debian:bookworm-slim AS build

ARG LIBCSP_COMMIT=48f7fb0b57f610bf65bab1aa2d1357c3b9722782
ARG CSP_21=0

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential ca-certificates cmake git ninja-build \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --quiet https://github.com/libcsp/libcsp.git /libcsp \
    && git -C /libcsp checkout --quiet "${LIBCSP_COMMIT}" \
    && test "$(git -C /libcsp rev-parse HEAD)" = "${LIBCSP_COMMIT}" \
    && test -z "$(git -C /libcsp status --porcelain)"

# CSP_21 changes libcsp's transmit CRC coverage from payload only to header + payload
RUN if [ "${CSP_21}" = "1" ]; then printf '%s' '-DCSP_21=1' > /cflags; else : > /cflags; fi \
    && cmake -G Ninja -S /libcsp -B /build -DBUILD_SHARED_LIBS=OFF "-DCMAKE_C_FLAGS=$(cat /cflags)" \
    && cmake --build /build

COPY interop_node.c /src/interop_node.c

RUN gcc -std=gnu11 -Wall -Wextra -Wpedantic $(cat /cflags) \
    -I/libcsp/include -I/libcsp/src -I/libcsp/examples -I/build/include \
    /src/interop_node.c /libcsp/examples/csp_posix_helper.c /build/libcsp.a \
    -lpthread -lrt -lutil -o /interop-node

FROM debian:bookworm-slim
COPY --from=build /interop-node /usr/local/bin/interop-node
ENTRYPOINT ["/usr/local/bin/interop-node"]
