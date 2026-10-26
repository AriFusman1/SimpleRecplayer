#!/usr/bin/env python3
"""
Creates samples/example.pcap: a 60-second recording, as dumpcap -i any would capture it on a
service host (10.20.0.10). Uses only the Python standard library.

Traffic in the file (times are seconds from the start of the recording):

  TCP server mode  - source 10.20.0.50:41000 connects to our service on port 5000 and sends
                     "ORDER" lines in bursts (several messages close together, then a pause).
                     A second source 10.20.0.51 sends to port 5001 every 2 s.
                     The service replies "ACK" lines and pure TCP ACKs (no payload, skipped).
  UDP server mode  - source 10.20.0.60 sends "PRICE" datagrams to port 6000 every 250 ms,
                     and source 10.20.0.61 sends "HEARTBEAT" datagrams to port 6001 every 5 s.
  TCP client mode  - our service connected out to 10.20.0.70:7000, which sends "STATUS" lines
                     every 1.5 s (filter on SOURCE port 7000).
  Noise            - ARP, IPv6 and ICMP packets (ignored by the parser),
                     3 IP fragments and 2 packets truncated by the snap length (skipped + warned).

Usage:  python3 make_example_pcap.py [output.pcap]
"""
import struct
import sys

START = 1761552000  # 27/10/2025 10:00:00 Israel time (07:00:00 UTC)
SERVICE = "10.20.0.10"
LINKTYPE_SLL2 = 276


def ip_bytes(address):
    return bytes(int(part) for part in address.split("."))


def ipv4(src, dst, protocol, l4, ident=1, flags_and_offset=0x4000):
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(l4), ident, flags_and_offset,
                         64, protocol, 0, ip_bytes(src), ip_bytes(dst))
    return header + l4


def tcp(src_port, dst_port, payload, seq=1):
    return struct.pack("!HHIIBBHHH", src_port, dst_port, seq, 1, 5 << 4, 0x18, 65535, 0, 0) + payload


def udp(src_port, dst_port, payload):
    return struct.pack("!HHHH", src_port, dst_port, 8 + len(payload), 0) + payload


def sll2(protocol, packet):
    # SLL2 header: protocol(2) reserved(2) ifindex(4) arphrd(2) packet_type(1) addr_len(1) addr(8)
    return struct.pack("!HHIHBB8s", protocol, 0, 2, 1, 0, 6, b"\x02\x42\xac\x11\x00\x02\x00\x00") + packet


def ip_frame(packet):
    return sll2(0x0800, packet)


packets = []  # (time in seconds from START, frame bytes, captured length or None)


def add(t, frame, captured=None):
    packets.append((t, frame, captured))


# ---- TCP server mode: orders to port 5000 in bursts, with replies ----
seq = 1
order_id = 1000
t = 0.5
while t < 58:
    for i in range(4):  # a burst of 4 orders, 15 ms apart
        line = f"ORDER id={order_id} side={'BUY' if order_id % 2 else 'SELL'} qty={(order_id % 7 + 1) * 100} px={100 + order_id % 13}.25\n"
        add(t + i * 0.015, ip_frame(ipv4("10.20.0.50", SERVICE, 6, tcp(41000, 5000, line.encode(), seq))))
        seq += len(line)
        add(t + i * 0.015 + 0.002, ip_frame(ipv4(SERVICE, "10.20.0.50", 6, tcp(5000, 41000, b""))))  # pure ACK
        add(t + i * 0.015 + 0.004, ip_frame(ipv4(SERVICE, "10.20.0.50", 6, tcp(5000, 41000, f"ACK {order_id}\n".encode()))))
        order_id += 1
    t += 3.0

# second TCP source to port 5001
for n, t in enumerate(x * 2.0 + 1.0 for x in range(29)):
    add(t, ip_frame(ipv4("10.20.0.51", SERVICE, 6, tcp(42000, 5001, f"POSITION seq={n} open=12\n".encode()))))

# ---- UDP server mode: prices to 6000, heartbeats to 6001 ----
for n in range(240):
    t = 0.1 + n * 0.25
    price = f"PRICE sym=ABC seq={n} bid={50 + (n % 20) / 10:.2f} ask={50.1 + (n % 20) / 10:.2f}"
    add(t, ip_frame(ipv4("10.20.0.60", SERVICE, 17, udp(33000, 6000, price.encode()))))
for n in range(12):
    add(n * 5.0 + 0.2, ip_frame(ipv4("10.20.0.61", SERVICE, 17, udp(33001, 6001, f"HEARTBEAT {n}".encode()))))

# ---- TCP client mode: the remote server 10.20.0.70:7000 sends status to our service ----
for n in range(39):
    t = 0.8 + n * 1.5
    add(t, ip_frame(ipv4("10.20.0.70", SERVICE, 6, tcp(7000, 51234, f"STATUS seq={n} state=OK load={n % 9}\n".encode()))))
    add(t + 0.003, ip_frame(ipv4(SERVICE, "10.20.0.70", 6, tcp(51234, 7000, b""))))  # pure ACK

# ---- noise the parser should ignore or skip ----
for n in range(6):
    add(n * 10.0 + 0.05, sll2(0x0806, b"\x00\x01\x08\x00\x06\x04\x00\x01" + b"\x00" * 20))           # ARP
    add(n * 10.0 + 0.06, sll2(0x86DD, b"\x60" + b"\x00" * 39))                                      # IPv6
    add(n * 10.0 + 0.07, ip_frame(ipv4("10.20.0.1", SERVICE, 1, b"\x08\x00\x00\x00\x00\x01\x00\x01")))  # ICMP ping
for n in range(3):  # IP fragments of a big UDP datagram (more-fragments flag set / offset > 0)
    flags = 0x2000 if n < 2 else 0
    add(20.0 + n * 0.001, ip_frame(ipv4("10.20.0.60", SERVICE, 17, udp(33000, 6000, b"F" * 64),
                                        ident=777, flags_and_offset=flags | (n * 185))))
for n in range(2):  # packets cut by a small snap length
    big = ip_frame(ipv4("10.20.0.50", SERVICE, 6, tcp(41000, 5000, b"Z" * 1200)))
    add(30.0 + n * 0.5, big, captured=96)

packets.sort(key=lambda p: p[0])

output = sys.argv[1] if len(sys.argv) > 1 else "example.pcap"
with open(output, "wb") as f:
    # Global header (little-endian, microseconds): magic, version 2.4, zone, sigfigs, snaplen, link type
    f.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 262144, LINKTYPE_SLL2))
    for t, frame, captured in packets:
        micros = round(t * 1_000_000)
        seconds, fraction = START + micros // 1_000_000, micros % 1_000_000
        data = frame[:captured] if captured else frame
        # Packet header: seconds, microseconds, captured length, original length
        f.write(struct.pack("<IIII", seconds, fraction, len(data), len(frame)))
        f.write(data)

print(f"Wrote {len(packets)} packets to {output}")
