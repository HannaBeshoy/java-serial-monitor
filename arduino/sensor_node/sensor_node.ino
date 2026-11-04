// Sensor node for java-serial-monitor (Arduino Uno or Nano, 5 V).
//
// Sends one line every 250 ms at 115200 baud, 8N1:
//   $SN,<seq>,<uptime ms>,T=<LM35 ADC counts>,D=<HC-SR04 echo us>,L=<LDR ADC counts>*<XOR checksum>
// Raw values only: the PC converts them (see the Sensor classes), so the conversion can be
// changed and tested without reflashing the board.
//
// Wiring
//   LM35     +Vs -> 5V, Vout -> A0, GND -> GND
//   LDR      5V -> LDR -> A1 -> 10 kOhm -> GND   (voltage divider)
//   HC-SR04  VCC -> 5V, Trig -> D9, Echo -> D10, GND -> GND

const uint8_t PIN_TEMP = A0;
const uint8_t PIN_LIGHT = A1;
const uint8_t PIN_TRIG = 9;
const uint8_t PIN_ECHO = 10;
const unsigned long PERIOD_MS = 250;
const unsigned long ECHO_TIMEOUT_US = 25000;   // about 4.3 m; pulseIn returns 0 after this

unsigned long seq = 0;
unsigned long nextSend = 0;

uint8_t checksum(const char *s) {               // XOR of every character, like NMEA 0183
  uint8_t x = 0;
  while (*s) x ^= (uint8_t)*s++;
  return x;
}

unsigned long readEchoMicros() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);                 // a 10 us pulse starts one measurement
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);
  return pulseIn(PIN_ECHO, HIGH, ECHO_TIMEOUT_US);
}

int readAverage(uint8_t pin) {                  // 4 samples: a little less ADC noise
  long sum = 0;
  for (uint8_t i = 0; i < 4; i++) sum += analogRead(pin);
  return (int)(sum / 4);
}

void setup() {
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  Serial.begin(115200);
}

void loop() {
  unsigned long now = millis();
  if ((long)(now - nextSend) < 0) return;       // overflow-safe timing, no delay()
  nextSend = now + PERIOD_MS;

  char body[80];
  snprintf(body, sizeof body, "SN,%lu,%lu,T=%d,D=%lu,L=%d",
           seq++, now, readAverage(PIN_TEMP), readEchoMicros(), readAverage(PIN_LIGHT));

  char tail[6];
  snprintf(tail, sizeof tail, "*%02X", checksum(body));
  Serial.print('$');
  Serial.print(body);
  Serial.println(tail);                         // println ends the line with \r\n; the PC ignores the \r
}
