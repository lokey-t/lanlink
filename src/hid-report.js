// Report IDs are carried by the GATT Report Reference descriptor, not in the payload.
export const reportMap = [
  0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xa1, 0x00,
  0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
  0x95, 0x03, 0x81, 0x02, 0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
  0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7f,
  0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xc0, 0xc0,
  0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x85, 0x02, 0x05, 0x07,
  0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
  0x75, 0x08, 0x95, 0x01, 0x81, 0x03,
  0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x95, 0x06, 0x81, 0x00, 0xc0,
];

const usages = new Map(Object.entries({
  Enter:40, Escape:41, Backspace:42, Tab:43, Space:44, Minus:45, Equal:46,
  BracketLeft:47, BracketRight:48, Backslash:49, Semicolon:51, Quote:52, Backquote:53,
  Comma:54, Period:55, Slash:56, CapsLock:57, Insert:73, Home:74, PageUp:75, Delete:76,
  End:77, PageDown:78, ArrowRight:79, ArrowLeft:80, ArrowDown:81, ArrowUp:82,
}));
for (let i = 0; i < 26; i++) usages.set('Key' + String.fromCharCode(65 + i), 4 + i);
for (let i = 1; i <= 12; i++) usages.set('F' + i, 57 + i);
for (let i = 0; i < 10; i++) {
  usages.set('Digit' + ((i + 1) % 10), 30 + i);
  usages.set('Numpad' + ((i + 1) % 10), 89 + i);
}
const modifiers = ['ControlLeft','ShiftLeft','AltLeft','MetaLeft','ControlRight','ShiftRight','AltRight','MetaRight'];
const mouseBits = [1, 4, 2];
const step = n => Math.min(127, Math.max(-127, n));
export const neutralReports = [[1, [0,0,0,0]], [2, [0,0,0,0,0,0,0,0]]];

export class HidReports {
  buttons = 0;
  keys = new Set();
  fractions = [0,0,0];

  encode(event) {
    if (event.kind === 'key') {
      if (!modifiers.includes(event.code) && !usages.has(event.code)) return [];
      if (event.down) this.keys.add(event.code); else this.keys.delete(event.code);
      const mask = modifiers.reduce((bits, code, index) => this.keys.has(code) ? bits | 1 << index : bits, 0);
      const keys = [...this.keys].map(code => usages.get(code)).filter(Boolean);
      return [[2, [mask, 0, ...(keys.length > 6 ? Array(6).fill(1) : keys.concat(Array(6 - keys.length).fill(0)))]]];
    }
    if (event.kind === 'button') {
      const mask = mouseBits[event.button];
      this.buttons = event.down ? this.buttons | mask : this.buttons & ~mask;
      return [[1, [this.buttons, 0, 0, 0]]];
    }
    const amounts = event.kind === 'wheel' ? [0, 0, event.delta / 120] : [event.dx ?? 0, event.dy ?? 0, 0];
    const remaining = amounts.map((value, i) => {
      const total = Math.max(-32000, Math.min(32000, value)) + this.fractions[i];
      const whole = Math.trunc(total);
      this.fractions[i] = total - whole;
      return whole;
    });
    const reports = [];
    while (remaining.some(Boolean)) {
      const chunk = remaining.map(step);
      chunk.forEach((value, i) => remaining[i] -= value);
      reports.push([1, [this.buttons, ...chunk.map(value => value & 255)]]);
    }
    return reports;
  }
}
