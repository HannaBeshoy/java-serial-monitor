# Line protocol

The sensor node sends plain ASCII text, one frame per line, at 115200 baud, 8 data bits, no parity, 1 stop bit (8N1).

```text
$SN,42,123456,T=61,D=2001,L=512*63\r\n
```

| Part | Example | Meaning |
| --- | --- | --- |
| start | `$` | start of a frame; anything before it is not a frame |
| node | `SN` | node name, 1–16 letters, digits or `_` |
| seq | `42` | sequence number, +1 per frame, starts at 0 after a reset |
| uptime | `123456` | milliseconds since the node started (`millis()`) |
| values | `T=61,D=2001,L=512` | one or more `KEY=integer`, keys are 1–8 upper-case letters or digits starting with a letter, each key at most once |
| checksum | `*63` | XOR of every character between `$` and `*`, as two hex digits |
| end | `\r\n` or `\n` | the parser ignores the `\r` and surrounding spaces |

The longest accepted line is 200 characters.

## The checksum

The same check as NMEA 0183 GPS sentences: XOR all bytes of the body.

```java
int x = 0;
for (char c : "SN,42,123456,T=61,D=2001,L=512".toCharArray()) x ^= c;
String.format("%02X", x);          // "63"
```

It catches every single-bit error and every change of one character, which is what a noisy cable or a wrong baud rate usually causes. It does not catch two errors that cancel out, or swapped characters; a CRC-16 would, and is the planned upgrade.

## Why raw values

The node sends ADC counts and echo times, not °C or cm. The conversion lives in the Java `Sensor` classes, so it can be unit-tested and changed without reflashing the Arduino, and the log keeps what was really measured.

## How the PC classifies a line

| Result | Example | Counted as |
| --- | --- | --- |
| valid frame | the line above | frame |
| syntax error | `garbage`, no `$`, no `*XX`, fewer than 4 fields, too long | bad |
| checksum error | `$SN,42,123456,T=71,D=2001,L=512*63` | bad, and checksum |
| field error | valid checksum but `seq=x`, `T=1.5`, `t=3`, a key twice | bad |
| sequence gap | 7 then 9 | 1 lost (includes frames that arrived damaged) |
| smaller sequence | 120 then 0 | node restarted, not lost |
| queue full on a live port | | dropped (not counted as lost) |
| key with no sensor | `Q=5` | unknown key, the other values are used |
| raw value meaning "nothing" | `D=0` (no echo), counts outside 0–1023 | reading marked invalid, left out of statistics |
