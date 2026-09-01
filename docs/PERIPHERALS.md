# BIZAPP POS - Peripherals

How to connect a receipt printer, a cash drawer, a customer display and a scale, what to type
into the configuration, and what to do when it does not work.

This document covers **BIZAPP POS 4.0**. It is written for the person setting up the till, not
for a developer. Every configuration string in here is a literal string you can copy.

> **Honest summary before you start.** The ESC/POS driver described here was developed and
> verified **without any hardware**. Every command sequence is asserted byte for byte against
> the published ESC/POS command specification by an automated test suite (see
> [13. Verifying without hardware](#13-verifying-without-hardware)), and the network and file
> transports are proven end to end against a local listener. No physical printer, drawer,
> display or scale was connected at any point. The first real till to print is the first real
> test. Section 13 lists exactly what is proven and what is not.

---

## Contents

1. [Where the settings live](#1-where-the-settings-live)
2. [Syntax rules](#2-syntax-rules)
3. [Receipt printer](#3-receipt-printer)
4. [Options reference](#4-options-reference)
5. [Customer display](#5-customer-display)
6. [Scale](#6-scale)
7. [Platform limits](#7-platform-limits)
8. [What the POS cannot tell you](#8-what-the-pos-cannot-tell-you)
9. [Troubleshooting](#9-troubleshooting)
10. [Star printers](#10-star-printers)
11. [Behaviour changes in this release](#11-behaviour-changes-in-this-release)
12. [Not implemented](#12-not-implemented)
13. [Verifying without hardware](#13-verifying-without-hardware)

---

## 1. Where the settings live

All peripheral settings live in a single plain-text properties file in the home directory of
the user who runs the till:

| Platform | File |
|---|---|
| Windows | `C:\Users\<you>\nordpos.properties` |
| macOS   | `/Users/<you>/nordpos.properties` |
| Linux   | `/home/<you>/nordpos.properties` |

The keys that matter here:

| Key | Device |
|---|---|
| `machine.printer` | Receipt printer 1. This is the one that prints the sale. |
| `machine.printer.2` | Receipt printer 2 (kitchen, bar, order printer). |
| `machine.printer.3` | Receipt printer 3. |
| `machine.display` | Customer-facing display (pole display / VFD). |
| `machine.scale` | Weighing scale. |
| `machine.fiscalprinter` | Fiscal printer. Not implemented - see [12](#12-not-implemented). |
| `machine.labelprinter` | Label printer. Not implemented - see [12](#12-not-implemented). |
| `machine.pludevice` | PLU / input-output device. Not implemented - see [12](#12-not-implemented). |
| `machine.printername` | **A different thing.** This is the OS print queue used for *reports* (JasperReports), not for receipts. Leave it as `(Default)` unless you know you need to change it. |

### 1.1 Two ways to change them

**Route A - edit the file.** Close BIZAPP POS, open `nordpos.properties` in a text editor, change
the line, save, start BIZAPP POS again. Peripheral settings are read once at startup, so a
restart is always required.

```properties
machine.printer=escpos:network,192.168.1.50,9100
machine.printer.2=Not defined
machine.printer.3=Not defined
machine.display=Not defined
machine.scale=Not defined
```

**Route B - the Configuration screen.** Go to **Configuration > Hardware**. For the device you
want, choose **`extended`** from the drop-down. Two text boxes appear:

| Box | What to type | Result in the file |
|---|---|---|
| Driver | `escpos` | `machine.printer=escpos:network,192.168.1.50,9100` |
| Settings | `network,192.168.1.50,9100` | (the two boxes are joined with a `:`) |

> **Press Save.** The two `extended` text boxes only mark the form as changed when you press
> **Enter** inside them. If you type a value and then click straight to another screen without
> pressing Save, the edit is thrown away and you are not warned. Type the value, then press the
> **Save** button, then restart the application.

The `extended` option is available for the receipt printers, the customer display and the scale.

---

## 2. Syntax rules

Every device setting has the same shape:

```
<driver>:<setting>,<setting>,<setting>...
```

Four rules, and they are absolute:

1. **The first colon, and only the first colon, separates the driver name from its settings.**
   `escpos:network,192.168.1.50,9100` means driver `escpos`, settings `network`,
   `192.168.1.50`, `9100`.
2. **Everything after that first colon is separated by commas.**
3. **A comma can never appear inside a value.** Not in a printer queue name, not in a file path,
   not in an option value. If your print queue has a comma in its name, rename the queue.
4. **Colons inside later values are safe and are kept exactly as typed.** `C:\Temp\receipt.bin`,
   `[fd00::1]:9100` and `logo=nv:1` all work as single comma-separated values.
5. **The second setting is reserved. Never make it the bare word `receipt` or `standard`.**
   BIZAPP POS looks at the second comma-separated setting to decide how to build the printer,
   and those two words select the report-paper sizes. A queue or a file called `receipt` is a
   start-up hazard - see
   [3.2](#32-printer-or-usb-a-usb-or-shared-printer).

**Never use a colon as a parameter separator.** This is the single most common mistake:

```properties
# WRONG - parses as driver "escpos" with one meaningless setting
machine.printer=escpos:network:192.168.1.50:9100

# RIGHT
machine.printer=escpos:network,192.168.1.50,9100
```

The wrong form does not produce an error message. It silently falls back to a "null" printer:
the sale completes, nothing prints, and there is nothing in the log. Check the commas first.

---

## 3. Receipt printer

The driver name for a thermal receipt printer is **`escpos`**. It speaks the Epson ESC/POS
command language, which is what almost every thermal receipt printer on the market understands,
either natively or in an emulation mode.

```
escpos:<transport>,<positional settings>[,<option>=<value>]...
```

There are four transports - four ways of getting the bytes to the printer:

| Transport | Use it when | Works on |
|---|---|---|
| [`network`](#31-network-the-recommended-way) | The printer has an Ethernet or Wi-Fi port | Windows, macOS, Linux |
| [`printer`](#32-printer-or-usb-a-usb-or-shared-printer) (alias `usb`) | The printer is plugged into this PC by USB and has a driver installed | Windows, Linux |
| [`file`](#33-file-a-device-node-or-a-file) | You know the device node (`/dev/usb/lp0`, `LPT1`) | Windows, macOS, Linux |
| [`serial`](#34-serial-an-rs-232-printer) | The printer has a 9-pin RS-232 plug | Windows, Linux (**not** Apple Silicon Macs) |

The other receipt-printer drivers that shipped before this release are unchanged and still work:
`screen` (on-screen preview), `printer:<queue>,receipt` (print through the OS driver as a page of
graphics) and `plaintext:file,<path>`. Use `escpos` when you want a real thermal receipt with a
cash-drawer kick, an auto-cut, bold text and a barcode.

### 3.1 `network` (the recommended way)

```
escpos:network,<host>,<port>
```

Almost all network receipt printers listen for raw bytes on **TCP port 9100**. Find the printer's
IP address (the self-test page prints it - hold FEED while switching the printer on), give it a
fixed address on your router, and type it in.

| What you want | The exact string |
|---|---|
| Printer at 192.168.1.50 | `machine.printer=escpos:network,192.168.1.50,9100` |
| Same, port left out (9100 is assumed) | `machine.printer=escpos:network,192.168.1.50` |
| Printer with a DNS name | `machine.printer=escpos:network,tm-t88.shop.local,9100` |
| Kitchen printer as printer 2 | `machine.printer.2=escpos:network,192.168.1.51,9100` |
| With options | `machine.printer=escpos:network,192.168.1.50,9100,cp=1252,cut=full,feed=6` |
| IPv6 address | `machine.printer=escpos:network,[fd00::1]:9100` |

How the settings are read:

| String | driver | 1 | 2 | 3 | 4... |
|---|---|---|---|---|---|
| `escpos:network,192.168.1.50,9100` | `escpos` | `network` | `192.168.1.50` | `9100` | - |
| `escpos:network,192.168.1.50,9100,cp=1252,cut=full` | `escpos` | `network` | `192.168.1.50` | `9100` | `cp=1252`, `cut=full` |

If the port is missing, empty, not a number, zero, negative or above 65535, **9100 is used**.
An IPv6 address must use the bracketed single-value form `[address]:port`.

**One connection at a time.** Most printer network cards accept only one session on port 9100.
BIZAPP POS opens a connection, sends the whole receipt, and closes it again immediately, so
several tills can share one printer - but they cannot print at the same instant, and a second
till printing at exactly that moment will get a refusal. This is a property of the printer, not
of the POS.

### 3.2 `printer` or `usb` (a USB or shared printer)

```
escpos:printer,<print queue name>
escpos:usb,<print queue name>
```

`printer` and `usb` mean exactly the same thing. Use whichever you find easier to remember. This
sends the raw ESC/POS bytes to a print queue that the operating system already knows about, which
is how a USB, parallel or network-shared thermal printer is normally driven.

| What you want | The exact string |
|---|---|
| A queue you created called `Bizapp-Thermal` | `machine.printer=escpos:printer,Bizapp-Thermal` |
| A queue with spaces in the name | `machine.printer=escpos:printer,EPSON TM-T20III Receipt` |
| Same, forcing code page 437 | `machine.printer=escpos:printer,EPSON TM-T20III Receipt,cp=437` |
| Using the `usb` spelling | `machine.printer=escpos:usb,Generic-Text-Only` |

**Spaces in a queue name are fine. Commas are not.**

Three things to get right:

1. **The queue must be a RAW queue.** On Windows, install the printer with the driver
   **"Generic / Text Only"**, or use the vendor's own driver in its raw/passthrough mode. On
   Linux, create the queue with the `raw` model. If you point this at a normal graphics driver,
   the printer will spit out several pages of garbage characters instead of a receipt, and there
   is no warning - see [8. What the POS cannot tell you](#8-what-the-pos-cannot-tell-you).
2. **Type the name by hand.** The ESC/POS queue name will **not** appear in the printer drop-down
   on the Configuration screen. That drop-down lists only queues that can accept formatted page
   output, and a raw queue is not one of them. Use the `extended` route (see
   [1.1](#11-two-ways-to-change-them)) and type the name.
3. **Use the name from the POS drop-down, not from `lpstat`.** On Linux and macOS these are two
   different strings: the POS shows the queue's *description* (`printer-info`), while `lpstat -p`
   shows its *short name* (`printer-name`). If in doubt, open **Configuration > Hardware**, look
   at the printer drop-down, and copy a name from there exactly, spaces and all.

> **Never call the queue `receipt` or `standard`.** BIZAPP POS routes on the **second**
> comma-separated setting. When that setting is literally `receipt` or `standard`, the whole
> string is sent down the report-paper branch, which reads `paper.receipt.*` /
> `paper.standard.*` out of `nordpos.properties` and converts four of them to integers with
> no guard at all. A stock properties file carries those keys, so a stock till does still
> start and does still get an ESC/POS printer; but if any one of them is missing, empty or
> not a number - an upgraded file, a hand-edited file - the application aborts during start
> up with `java.lang.NumberFormatException` **before any window appears**, and the message
> mentions neither your printer nor your queue. Both outcomes were measured here. The same
> trap catches `escpos:file,receipt` and `escpos:file,standard`.
>
> Give the queue any other name. `Bizapp-Thermal` is used throughout this document precisely
> because it cannot collide.

**This is a Windows and Linux transport.** macOS removed raw print queues - see
[7. Platform limits](#7-platform-limits). Mac users should use `escpos:network` or `escpos:file`.

### 3.3 `file` (a device node or a file)

```
escpos:file,<path>
```

Writes the ESC/POS bytes straight to a path. Use this when you know the device node the printer
appears as, or when you want to capture the bytes to inspect them.

| What you want | The exact string |
|---|---|
| A USB printer on Linux | `machine.printer=escpos:file,/dev/usb/lp0` |
| A USB-serial printer on a Mac | `machine.printer=escpos:file,/dev/cu.usbserial-A50285BI` |
| A parallel printer on Windows | `machine.printer=escpos:file,LPT1` |
| Capture the bytes of a **drawer kick** to inspect | `machine.printer=escpos:file,C:\Temp\escpos.bin` then press **Open drawer** |

The drive-letter colon in `C:\Temp\escpos.bin` is safe - it is inside a comma-separated value.

Do not use `receipt` or `standard` as the file name - see the callout in
[3.2](#32-printer-or-usb-a-usb-or-shared-printer).

> ### `escpos:file` keeps the LAST job only, and a cash sale is two jobs
>
> The file is opened **without append and is therefore truncated at the start of every
> job**, not once per receipt. That is exactly right for a device node, where each job is a
> separate burst of bytes down a wire and nothing is meant to accumulate. It is ruinous for
> a file you wanted to read afterwards.
>
> A "job" is one receipt, or one drawer kick, or one paper cut - whatever the driver hands
> to the transport in one go. `Printer.Ticket.xml`, the receipt template BIZAPP POS ships
> with, emits `<opendrawer/>` **after** `</ticket>` on every cash payment, so a cash sale is
> **two** jobs: the receipt, then the kick. The kick truncates the file and overwrites the
> receipt.
>
> Measured here, on the real template parser writing to a real file: the receipt job left 35
> bytes, and after the drawer kick the file held **7 bytes**, `1B 40 1B 70 00 19 FA` -
> `ESC @` followed by the pin-2 pulse. The receipt was gone. The same happens after every
> press of the **Open drawer** button.
>
> **So: `escpos:file` is for device nodes and for one-shot capture, never for a receipt
> log.** If you want to capture a whole receipt for inspection, either configure a
> non-cash payment (no `<opendrawer/>`, so one job) or copy the file the instant the
> receipt job finishes. If you want a durable, appended record of what was printed, this
> transport cannot give you one and neither can any other setting in this document.

On Linux you will normally need to add the till user to the `lp` group before `/dev/usb/lp0` is
writable.

> **`escpos:file` reports nothing on screen either.** The file transport never records a
> delivery error, so the printer status panel described in
> [3.5](#35-your-first-test-print) always says "no error reported" for it, even when the
> path does not exist or is not writable. That failure appears in the application log and
> nowhere else.

### 3.4 `serial` (an RS-232 printer)

```
escpos:serial,<port>,<baud>,<databits>,<stopbits>,<parity>[,flow=none|xonxoff|rtscts]
```

| What you want | The exact string |
|---|---|
| COM3 at 9600 8-N-1 | `machine.printer=escpos:serial,COM3,9600,8,1,none` |
| A USB-serial adapter on Linux, 19200, XON/XOFF | `machine.printer=escpos:serial,/dev/ttyUSB0,19200,8,1,none,flow=xonxoff` |
| COM3 with options | `machine.printer=escpos:serial,COM3,9600,8,1,none,cp=437,cut=full` |

Accepted values:

| Setting | Values | Default if you get it wrong |
|---|---|---|
| baud | `2400` `4800` `9600` `19200` `38400` `57600` `115200` | `9600` |
| databits | `5` `6` `7` `8` | `8` |
| stopbits | `1` `2` | `1` |
| parity | `none` `even` `odd` | `none` |
| `flow=` | `none` `xonxoff` `rtscts` | `none` |

These must match the printer's own DIP switches. Print the printer's self-test page (hold FEED
while switching it on) - it prints its current baud rate, parity and handshaking.

> **Set the flow control.** Serial thermal printers overflow their small buffer part-way through
> a long receipt if handshaking is not enabled. If the self-test page says XON/XOFF, add
> `flow=xonxoff`. If it says DTR/DSR or RTS/CTS, add `flow=rtscts`. Leaving `flow=none` when the
> printer expects handshaking gives you receipts that stop half way down, or print gibberish
> after the first ten lines.

> **Serial does not work on Apple Silicon Macs.** See [7. Platform limits](#7-platform-limits).
> On an M-series Mac this setting produces a visible "Serial port unavailable" tab explaining
> why; the application starts normally and nothing crashes.

### 3.5 Your first test print

1. Set `machine.printer` and restart BIZAPP POS.
2. Ring up a cheap item and complete the sale. The receipt should print, the drawer should kick,
   and the paper should be cut.

If nothing at all comes out, go to [9. Troubleshooting](#9-troubleshooting).

> **Where the messages go.** Open the **Printer** view. An ESC/POS printer now has its own
> tab there, named `ReceiptPrinter.EscPos`, showing three lines: what it is, the configured
> **Target** (the host and port, the queue name, the path or the serial port), any
> **Options** it had to ignore, and the **Status** - either `no error reported for the last
> job sent` or the full text of the last failure, naming the address or queue you have to
> correct. The panel re-reads all three every time it is painted, so switching away and back
> refreshes it; there is no polling and no timer.
>
> Two things that tab is **not**. It is not a printer-status display: it tells you about the
> last *job BIZAPP POS sent*, never about paper, cover or jam - see
> [8. What the POS cannot tell you](#8-what-the-pos-cannot-tell-you). And it says nothing at
> all on the `escpos:file` transport, which never records an error (see
> [3.3](#33-file-a-device-node-or-a-file)).
>
> Everything the tab shows is also written to the **application log** - the console window
> BIZAPP POS was started from, or the log file your installation writes - together with the
> things that have no tab at all: a configuration string that failed to parse, and a
> customer display or scale that could not be opened. Keep that window open while you set a
> printer up for the first time.

### 3.6 Testing the cash-drawer kick

There is an **Open drawer** button on the sales screen. It is available to the Manager and
Administrator roles, and it sends a drawer kick and nothing else - no receipt, no paper feed, no
cut. That makes it the fastest way to test the drawer: press it and listen. The drawer also fires
automatically at the end of a cash sale.

The drawer is wired to one of two pins in the RJ11 socket on the back of the printer, and there
is no way to detect which. If the drawer does not open, change the pin:

```properties
# default - most drawers
machine.printer=escpos:network,192.168.1.50,9100,drawer=pin2

# try this second - this fixes it most of the time
machine.printer=escpos:network,192.168.1.50,9100,drawer=pin5

# only if the printer documentation specifically asks for it
machine.printer=escpos:network,192.168.1.50,9100,drawer=realtime
```

If the drawer clicks but does not spring open, the solenoid is not getting a long enough pulse.
Lengthen it:

```properties
machine.printer=escpos:network,192.168.1.50,9100,drawer=pin2,drawerpulse=100
```

`drawerpulse` is in milliseconds and is limited to the range 20-200. The default is 50. It is
sent as the ON time of the pulse; the OFF time is fixed.

> **`drawer=realtime` ignores `drawerpulse`.** The real-time kick is a different command with
> a different, much coarser argument, and the driver sends it with a fixed argument of 5. In
> ESC/POS that argument is counted in units of 100 ms, so the real-time pulse is **500 ms ON
> and 500 ms OFF** - ten times the default `drawerpulse=50` and not adjustable. Adding
> `drawerpulse=` alongside `drawer=realtime` changes nothing. Use `realtime` only if the
> printer's own documentation asks for it; if you need to tune the pulse length, you need
> `drawer=pin2` or `drawer=pin5`.

A drawer that never opens on **any** pin setting, with **any** pulse length, is almost always a
cable problem: many cash drawers use an RJ11 or RJ12 cable that looks like a telephone cable but
is wired differently, and a printer-to-drawer cable is not the same as a modem cable.

### 3.7 Testing the paper cut

The cut happens once, at the end of every receipt, after a short paper feed. That is the
default and it is what you want:

```properties
# the default: feed 4 lines, then a partial cut (a small tab holds the receipt on)
machine.printer=escpos:network,192.168.1.50,9100

# same thing, written out
machine.printer=escpos:network,192.168.1.50,9100,cut=partial,feed=4

# cut the paper right through - the receipt drops
machine.printer=escpos:network,192.168.1.50,9100,cut=full

# do not cut at all - tear it off by hand
machine.printer=escpos:network,192.168.1.50,9100,cut=none

# feed more paper before cutting, so the printed area clears the tear bar
machine.printer=escpos:network,192.168.1.50,9100,feed=6
```

If the last two or three lines of the receipt are cut off, the cutter sits above the print head
and you need a bigger `feed`. Try `feed=6`, then `feed=8`. If the receipts come out with an
excessive blank tail, reduce it.

There is a fourth setting, `cut=template`, which hands the decision to the receipt template.
**On the templates BIZAPP POS ships with, it means the receipt is never cut at all.**

That is worth spelling out, because it is the opposite of what the name suggests. `cut=template`
emits a cut only when the template sent the receipt printer an explicit `<cutpaper/>` outside an
open `<line>`. Exactly two shipped templates contain a `<cutpaper/>` element at all -
`Printer.CloseCash.xml` and `Printer.PartialCash.xml` - and in both it sits inside
`<fiscalreport>`, which is routed to the fiscal printer, not to the receipt printer.
`Printer.Ticket.xml`, the one that prints your sales, has none. Measured: the same receipt came
out as 35 bytes ending in a partial cut under the default `cut=partial`, and as 32 bytes with no
cut byte anywhere under `cut=template`.

Use `cut=template` only with a template you wrote yourself that contains a `<cutpaper/>` as a
direct child of `<ticket>`. Measured with such a template, it produced exactly one cut, at the
end, of the type the element asked for.

**It will not shred your roll.** Whatever you set, the driver emits at most one cut per receipt,
at the end. A receipt with 60 `<text>` elements was measured emitting exactly one cut command.

---

## 4. Options reference

Options are `key=value` and may be added in any order, after the positional settings, on any
transport:

```properties
machine.printer=escpos:network,192.168.1.50,9100,cp=1252,cut=full,feed=6,drawer=pin5
```

| Key | Values | Default | What it does |
|---|---|---|---|
| `profile` | `epson`, `generic` | `epson` | Selects the printer start-up block. Use `generic` for a clone that misbehaves on the Epson-specific start-up commands. |
| `cp` | `437`, `850`, `852`, `858`, `866`, `1252`, `ascii`, `legacy` | `858` (`437` when `profile=generic`) | The character set. See the table below. |
| `cut` | `partial`, `full`, `none`, `template` | `partial` | How to cut at the end of a receipt. |
| `feed` | `0` to `255` | `4` | Lines of paper fed before the cut. |
| `drawer` | `pin2`, `pin5`, `realtime` | `pin2` | Which drawer pin to pulse. |
| `drawerpulse` | milliseconds, `20` to `200` | `50` | How long to pulse the drawer solenoid. |
| `font` | `a`, `b` | `a` | Font B is narrower. Use `font=b` on 58 mm paper so the standard 42-column layout fits. |
| `maxdots` | `1` to `1024` | `576` | Widest image the printer can take, in dots. Use `384` for 58 mm paper, `576` for 80 mm. 1024 is the ceiling the ESC/POS raster command itself can express - it carries the row width as a byte count whose documented maximum is 128 bytes - so a larger value is refused and the default stands. |
| `band` | `1` to `255` | `128` | Rows of image sent per command. Lower this if a logo prints half way and stops. |
| `threshold` | `0` to `255` | `128` | Brightness cut-off when turning a logo into black and white dots. Lower makes the logo lighter, higher makes it darker. |
| `barcode` | `native`, `raster` | `native` | `native` lets the printer draw the barcode. `raster` draws it as a picture instead - use it if your printer draws barcodes badly or not at all. |
| `bcheight` | `1` to `255` | `162` | Barcode height in dots. |
| `bcwidth` | `2` to `6` | `3` | Barcode bar width. Reduce it if a long barcode runs off the edge of the paper. |
| `logo` | `nv:<1-255>` | none | Prints a logo that has been stored **in the printer's own memory** at the given slot, at the top of every receipt. Store it with the printer vendor's utility first. |

**Bad options never stop the printer working.** An unknown key, or a value outside its range, is
recorded and ignored, and the default is used instead. Nothing is ever clamped to the nearest
legal value: an out-of-range number leaves the default in place. So
`escpos:network,192.168.1.50,9100,feed=999,bogus=1` prints normally, with `feed=4`, having
ignored both `feed=999` (out of range) and `bogus=1` (not a real option).

> **You can see what was ignored.** Every complaint is listed on the **Options** line of the
> printer's tab in the **Printer** view - `feed out of range '999'; unknown option 'bogus=1'`
> for the example above. If a setting seems to have no effect, look there first, then check
> your spelling against the table.

### 4.1 Character sets

The character set is chosen with `cp=`. Pick the one that matches the code page your printer is
set to - the printer's self-test page prints its current code page, and on most printers it is
also selectable by DIP switch.

| `cp=` | Covers | Use it for |
|---|---|---|
| `437` | Original IBM PC set | The safe default for older and cheaper clones. |
| `850` | Western European | French, Spanish, Portuguese, German, Italian. |
| `852` | Central European | Polish, Czech, Hungarian, Romanian, Croatian. |
| `858` | Western European plus the euro sign | **The default.** Same as 850 but with a euro symbol. |
| `866` | Cyrillic | Russian, Ukrainian, Bulgarian. |
| `1252` | Windows Western European | Windows-style Western European. |
| `ascii` | Plain English only | Anything that is not a plain letter, digit or punctuation mark prints as `?`. |
| `legacy` | The character conversion used by BIZAPP POS's older serial code | Only if a previous BIZAPP POS install printed accents correctly and this one does not. |

The character set and the printer instruction that selects it are always set together as a pair,
so they can never disagree. If a character set is somehow not available on the Java installation
running the till, the driver quietly falls back to plain ASCII rather than printing nonsense -
so accented characters would come out as `?` instead of as garbage.

Characters your chosen set cannot represent print as a question mark. If a receipt is full of
question marks, you chose too narrow a set - move from `ascii` to `858`, or to `852` or `866` for
Central European or Cyrillic text.

---

## 5. Customer display

A customer display (also called a pole display or VFD) is a small two-line screen facing the
customer. `machine.display` accepts these values:

| Value | What it is |
|---|---|
| `screen` | A panel inside the POS window. This is the default and is unchanged. |
| `window` | A separate window on the same computer, for a second monitor. Unchanged. |
| `Not defined` | No display. Unchanged. |
| `serial:...` | A real serial display. **New in this release.** |
| `network:...` | A real network display. **New in this release.** |

Note that here the driver name **is** the connection type - there is no `escpos:` prefix, because
`screen` and `window` already occupy that slot and a customer display speaks a different command
set from a printer.

```
serial:<port>,<baud>,<databits>,<stopbits>,<parity>[,options]
network:<host>,<port>[,options]
```

| What you want | The exact string |
|---|---|
| A serial display on COM1 | `machine.display=serial:COM1,9600,8,1,none` |
| A serial display on Linux, 20 columns | `machine.display=serial:/dev/ttyUSB0,9600,8,1,none,cols=20` |
| A USB-serial display on a Mac | `machine.display=serial:/dev/cu.usbserial-0001,9600,8,1,none` |
| A network display | `machine.display=network:192.168.1.60,9100` |

Options:

| Key | Values | Default | What it does |
|---|---|---|---|
| `cols` | a number | `20` | Characters per line. Almost every VFD is 20. |
| `cursor` | `wrap`, `us` | `wrap` | `wrap` relies on the display wrapping automatically at the end of line 1. If your display puts both lines on top of each other, or ignores line 2, use `cursor=us` to position each line explicitly. |
| `cp` | as in [4.1](#41-character-sets) | `437` | Character set. |

The display is driven by an Epson DM-D style command set, which is what the great majority of
2x20 pole displays understand. The POS refreshes it about four times a second and holds one
connection open for the life of the session.

> Serial customer displays do not work on Apple Silicon Macs - see
> [7. Platform limits](#7-platform-limits). A `network:` display works everywhere.

> **If a serial display fails to start, there is no message on screen.** Unlike the receipt
> printer, the customer display has nowhere in the user interface to show an error. It falls
> back to "no display" and writes one warning line to the application log. If you configured a
> display and nothing happens, check the log.

---

## 6. Scale

`machine.scale` accepts these values:

| Value | What it is |
|---|---|
| `Not defined` | No scale. The scale button is hidden. Unchanged. |
| `screen` | A keypad prompt asks the operator to type the weight. Unchanged. |
| `serial:...` | A real serial scale. **New in this release.** |
| `fake` | A random weight between 0 and 2 kg, for testing only. **Now actually works** - see [11](#11-behaviour-changes-in-this-release). |

```
serial:<port>,<baud>,<databits>,<stopbits>,<parity>
```

| What you want | The exact string |
|---|---|
| A scale on COM1 | `machine.scale=serial:COM1,9600,8,1,none` |
| A scale on Linux at 4800 8-O-1 | `machine.scale=serial:/dev/ttyUSB0,4800,8,1,odd` |
| Random weights, for trying the workflow out | `machine.scale=fake` |

### 6.1 Which scales this works with

**Only one protocol is supported**, the Openbravo / Epelsa "Dialog 1" protocol that BIZAPP POS
has always carried:

- The POS sends a single ENQ byte (hex `05`) to ask for a weight.
- The scale replies with the weight as plain ASCII digits, **in grams**, as a whole number.
- An RS byte (hex `1E`) ends the reply.

**It will not talk to a Toledo or Mettler-Toledo scale, a CAS scale, or an NCI / SCP scale.**
Those speak different protocols. If your scale is one of those, the POS will ask for a weight,
get an answer it cannot understand or no answer at all, and report an error. There is no
configuration setting that changes this; a different protocol needs a new driver.

### 6.2 What changed for the better

Previously, a scale that did not answer - wrong cable, wrong baud rate, wrong protocol, scale
switched off - caused the POS to record a weight of **0.000 kg** as if it were a real reading.
The operator got a zero-weight line on the sale and no warning at all.

Now a scale that does not answer within one second raises a clear error naming the port, and the
line is not added. This is a deliberate change and it means a misconfigured scale is now noisy
instead of silently wrong.

### 6.3 Limitations

- **The serial port stays open until the application is restarted.** If you change the scale
  settings, or want to use the port for something else, restart BIZAPP POS.
- **There is no error message on screen at startup.** Like the customer display, a scale that
  cannot be opened falls back to "no scale" with a warning in the application log only.
- Serial scales do not work on Apple Silicon Macs - see [7. Platform limits](#7-platform-limits).

---

## 7. Platform limits

### 7.1 Serial peripherals do not work on Apple Silicon (M1/M2/M3/M4) Macs

This is a hard limitation of this build. The serial library bundled with BIZAPP POS
(`nrjavaserial-3.11.0`) ships one macOS binary, `native/osx/libNRJavaSerial.jnilib`, and it is a
fat binary containing `i386` and `x86_64` only. There is no `arm64` slice anywhere in the jar, so
macOS on an Apple Silicon Mac cannot load it. (Checked here with `lipo -info` on the file
extracted from the shipped jar.)

Affected: `escpos:serial,...`, `machine.display=serial:...`, `machine.scale=serial:...` on an
Apple Silicon Mac running a native arm64 Java.

**Nothing crashes.** The application checks for this before it touches the serial library at all.
If you configure a serial receipt printer on an Apple Silicon Mac, the **Printer** view gains a
tab named `ReceiptPrinter.Unavailable` containing exactly this:

```
Serial port unavailable — /dev/cu.usbserial-0001

Serial peripherals are not supported on Apple Silicon (arm64) in this build. The bundled
nrjavaserial-3.11.0 native library contains only i386 and x86_64 code, so macOS cannot load
it on this Mac.

Use one of these instead:
  Receipt printer      escpos:network,<ip>,9100   (works on every platform)
                       escpos:file,/dev/cu.usbserial-XXXX
  Customer display     network:<ip>,<port>
  Scale                connect it to a Windows or Linux till, or run BIZAPP POS on an x86_64 JDK under Rosetta 2.

Windows (x86/x64) and Linux (x86/x64/ARM) are unaffected — the jar ships working natives for those.
```

A serial customer display or scale on the same machine falls back to "not present" and logs one
warning line; there is no on-screen tab for those two devices.

**On Windows and Linux the same tab appears when the port name is simply wrong**, listing the
ports that were actually detected on that machine and a line of examples for that operating
system. That is the fastest way to find out what your adapter is really called.

**Read the last line of that message carefully - "ARM" there means 32-bit ARM.** The jar's
Linux natives are `x86_32`, `x86_64`, `PPC`, `ARM` and `ARM_A8`, and both ARM builds are
32-bit ELF (checked here with `file` on the extracted libraries). There is **no `aarch64`
build in the jar at all**, so a 64-bit ARM Linux till - a Raspberry Pi running a 64-bit
Raspberry Pi OS, an ARM server, an ARM cloud VM - is in the same position as an Apple Silicon
Mac and cannot use `escpos:serial`, `machine.display=serial:` or `machine.scale=serial:`
either. The workarounds below apply to it unchanged, except that Rosetta 2 does not exist on
Linux; there, run a 32-bit ARM (armhf) Java, or use the network transport.

| Platform | Serial peripherals |
|---|---|
| Windows x86, x64 | Work |
| Linux x86, x64 | Work |
| Linux 32-bit ARM (armhf/armel) | Work |
| **Linux 64-bit ARM (aarch64)** | **Do not work - no native in the jar** |
| macOS Intel (x86_64) | Work |
| **macOS Apple Silicon (arm64)** | **Do not work - no native in the jar** |

None of the "Work" rows was exercised on hardware for this release; they are read off the
contents of the shipped jar, not off a printed receipt. See
[13.3](#133-what-the-suite-does-not-prove).

**Workarounds, in order of preference:**

1. **Use the network transport.** `escpos:network,<ip>,9100` for the printer and
   `network:<ip>,<port>` for the display work on every platform including Apple Silicon. This is
   the recommended answer.
2. **Use the file transport for a USB-serial printer.** A USB-to-serial adapter appears on macOS
   as a device node such as `/dev/cu.usbserial-A50285BI`. Writing to it directly bypasses the
   serial library entirely:
   `machine.printer=escpos:file,/dev/cu.usbserial-A50285BI`. The catch is that you cannot set the
   baud rate this way, so the adapter and printer must already agree - which for many USB-serial
   thermal printers they do, at the adapter's default. Try it; if the output is garbage, the
   baud rates disagree and this route will not work for you.
3. **Put the serial device on a till whose platform has a native in the jar** - Windows x86 or
   x64, Linux x86 or x64, or 32-bit ARM Linux. See the table above.
4. **Run BIZAPP POS on an x86_64 Java under Rosetta 2** (Apple Silicon only). This is offered as
   guidance, not as a promise: it follows from how Rosetta works, but it could not be tested here
   because no x86_64 Java was installed on the development machine.

### 7.2 `escpos:printer` / `escpos:usb` is a Windows and Linux transport

macOS no longer supports raw print queues. Attempting to create one gives:

```
lpadmin: Raw queues are no longer supported on macOS.
```

So `escpos:printer` and `escpos:usb` have nothing to point at on a Mac. Use `escpos:network` or
`escpos:file` instead.

### 7.3 The queue must be a RAW queue, and the POS cannot check that for you

If you point `escpos:printer` at a normal graphics printer driver, the printer receives ESC/POS
command bytes, tries to interpret them as a document, and produces pages of garbage. There is no
error.

**This cannot be detected programmatically.** The operating system's printing layer advertises
that every queue accepts arbitrary bytes - this was confirmed here on a cloud print queue driven
by a graphics driver, which claimed to accept raw data and would certainly have produced garbage.
There is no way for the POS to ask "are you really a raw queue?" and get an honest answer, so it
does not pretend to. Set the queue up correctly and do a test print.

Take the queue name from the printer drop-down on the Configuration screen, not from `lpstat`.
Those are two different strings on Linux and macOS.

---

## 8. What the POS cannot tell you

Be clear about this when training staff, because the natural assumption is wrong.

**On the print-queue (`escpos:printer` / `escpos:usb`) and file (`escpos:file`) transports there
is no return channel at all.** BIZAPP POS sends bytes in one direction and nothing comes back.
That means:

- **Paper out** is invisible. The sale completes, the POS reports success, and no receipt exists.
- **Cover open** is invisible.
- **A paper jam** is invisible.
- **The printer being switched off** is usually invisible.

The operator finds out by looking at the printer.

**On the network transport**, a genuine Epson Ethernet interface is capable of answering status
questions - but most installations sit behind a simple print server or a network card that only
accepts data, and a status query that goes unanswered would freeze the till in the middle of a
sale. **BIZAPP POS therefore never queries printer status during a sale.** What it does detect on
the network transport is a connection failure: if the printer refuses the connection, cannot be
reached, or the name cannot be resolved, that is recorded and written to the application log.
That tells you "I could not reach the printer", not "the printer is out of paper" - and it
appears in the log, not on the sales screen. The sale itself completes either way.

Do not build a workflow that depends on the POS noticing a printer problem. It will not.

---

## 9. Troubleshooting

### The cash drawer does not open

**Try `drawer=pin5` first.** This is by far the most common cause - drawers are wired to one of
two pins and there is no way to detect which.

```properties
machine.printer=escpos:network,192.168.1.50,9100,drawer=pin5
```

If it clicks but does not open, add `drawerpulse=100`. If neither pin does anything at all,
suspect the cable: a cash-drawer cable looks like a telephone cable and is wired differently.

### The receipt prints as pages of garbage characters

Two causes:

1. **The print queue is not a RAW queue** (`escpos:printer` / `escpos:usb` only). The printer is
   receiving ESC/POS commands through a graphics driver that is trying to render them. Recreate
   the queue as "Generic / Text Only" on Windows, or with the `raw` model on Linux. See
   [7.3](#73-the-queue-must-be-a-raw-queue-and-the-pos-cannot-check-that-for-you).
2. **It is a Star printer running in Star Line Mode.** Switch it to ESC/POS emulation - see
   [10. Star printers](#10-star-printers).

### Accented characters, currency symbols or Cyrillic print wrongly

Your `cp=` setting and the printer's own code-page setting disagree. Print the printer's
self-test page (hold FEED while switching the printer on) - it prints the code page it is
currently using. Then either set the printer's DIP switches to match your `cp=`, or set `cp=` to
match the printer.

If everything prints as `?`, you are on `cp=ascii` or on a set too narrow for your language.
Move to `858` for Western European, `852` for Central European, `866` for Cyrillic.

### Nothing prints at all, over the network

- **Ping the printer** from the till: `ping 192.168.1.50`. No reply means a network, cable or
  address problem, not a POS problem.
- **Check the application log** - the console window BIZAPP POS was started from, or the log file
  your installation writes. If BIZAPP POS could not reach the printer it says so there, naming
  the address, for example:
  `Receipt printer at 192.168.1.50:9100 refused the connection.`
- **Another till may be holding the connection.** Most printer network cards accept only one
  session on port 9100 at a time.
- **Check the port.** A few printers use something other than 9100. The self-test page says.
- **Check the commas.** `escpos:network:192.168.1.50:9100` (colons instead of commas) prints
  nothing and reports nothing. See [2. Syntax rules](#2-syntax-rules).

### Nothing prints at all, on a USB or shared printer

- Print a Windows or CUPS test page to the same queue. If that does not print, it is not a POS
  problem.
- Check the queue name is spelled exactly as it appears in the printer drop-down on the
  Configuration screen, spaces and all.
- Look in the application log. If the queue name was not found, the message names the queue you
  asked for and lists the queue names that were actually found on that machine.

### The receipt is never cut, and the next receipt prints on the same strip

You set `cut=template`, and no template BIZAPP POS ships with ever sends the receipt printer a
cut instruction - so no cut is emitted. Remove `cut=template` and use the default `cut=partial`
(or `cut=full`). `cut=template` is only for a custom template that carries an explicit
`<cutpaper/>` of its own. See [3.7](#37-testing-the-paper-cut).

The other reason for an uncut receipt is `cut=none`, which does exactly what it says, and a
printer with no cutter fitted, which no setting can help.

### The last lines of the receipt are cut off

The cutter sits above the print head. Increase the feed: `feed=6`, then `feed=8`.

### A logo prints half way down and then stops

Lower the band size: `band=64`, then `band=32`. Some printers cannot take 128 rows of image in
one command.

### A logo prints as a solid black rectangle

The image is too dark for the threshold, or is a transparent PNG on a dark background. Raise or
lower `threshold` (default `128`) until the logo reads correctly. Very light logos need a higher
threshold; very dark ones a lower.

### A barcode runs off the edge of the paper

Reduce `bcwidth` to `2`. If it still does not fit, the data is too long for the paper width -
shorten it, or use `barcode=raster`, which lets the POS draw and scale the symbol instead of the
printer.

### A serial printer stops part-way through a long receipt

The printer's buffer overflowed. Enable handshaking - `flow=xonxoff` or `flow=rtscts`, whichever
the printer's self-test page says it uses. See [3.4](#34-serial-an-rs-232-printer).

### Receipts look different from the on-screen preview

Expected. See [11. Behaviour changes](#11-behaviour-changes-in-this-release).

---

## 10. Star printers

**Star Line Mode is not implemented and will not be.** If you have a Star printer, switch it into
**ESC/POS emulation** - all the common models (TSP100, TSP143, TSP650, TSP700, mC-Print) support
it, via a DIP switch or Star's own configuration utility. Then configure it exactly as an Epson,
with the default `profile=epson`.

The reason this is not a matter of "just adding a Star option" is worth knowing, because it
explains why a wrong setting here is dangerous rather than merely ugly. Star's **cut** command is
byte-for-byte identical to ESC/POS's **feed n lines** command. A driver that guessed wrong would
not misprint a character - it would issue a cut command every time the receipt asked for a paper
feed, and shred the roll. Since no Star hardware was available to verify against, implementing
Star Line Mode from documentation alone was judged worse than declining to implement it.

---

## 11. Behaviour changes in this release

**(a) `machine.scale=fake` now returns a random weight.** Previously this setting was accepted
but did nothing at all - the scale button appeared and every weight came back empty. It now
returns a random weight between 0 and 2 kg, which is what it was always documented to do. If you
were using `fake` as a way of having a visible-but-inert scale button, you no longer get that
behaviour; use `Not defined` to hide the button, or `screen` to prompt the operator.

**(b) Thermal receipts will look different from the on-screen preview.** ESC/POS is the first
driver in BIZAPP POS that actually honours **bold** and **underline** in receipt templates. The
on-screen preview and the print-through-the-OS-driver path both silently discard them, so a
template that asks for a bold total has always previewed as plain text. On a thermal printer it
will now be bold. **This is a fix, not a regression** - but it will look like a difference, so
tell whoever compares them.

**(c) "average" and "thick" underline are the same thing on thermal hardware.** ESC/POS has
exactly two underline thicknesses. A template asking for `average` and one asking for `thick`
produce identical output. On-screen they may still differ.

---

## 12. Not implemented

These devices accept configuration values but do nothing, for every value, exactly as before this
release. Nothing has regressed; the gap is simply now written down instead of being silent.

| Device | Key | Why |
|---|---|---|
| Label printer | `machine.labelprinter` | Label printers speak ZPL, EPL or TSPL - completely different command languages from ESC/POS. This is a separate driver, not a setting. |
| Fiscal printer | `machine.fiscalprinter` | Fiscal printers are certified hardware whose behaviour is fixed by tax law in each country. Writing one without the certification documents and the hardware would be a compliance problem, not a driver problem. |
| PLU device | `machine.pludevice` | Deferred. |

BIZAPP POS still contains complete templates describing product-label and fiscal output. That
output currently goes nowhere.

Also not implemented in this release: a point-and-click editor for the settings described in this
document. Everything here is typed into the `extended` fields or into `nordpos.properties`
directly. The setting strings are now fixed, so an editor can be added later without changing any
of them.

---

## 13. Verifying without hardware

### 13.1 Running the test suite

From the top of the BIZAPP POS source tree:

```sh
sh tools/escpos-harness/run.sh
```

It builds the application, compiles the test harness against it and runs every check, printing
one line per test and a summary. At the time this document was written the last line read:

```
119 passed, 0 failed, 0 skipped
```

It exits non-zero if anything failed.

Useful flags:

```sh
sh tools/escpos-harness/run.sh --case bc-ean13     # run a single test
sh tools/escpos-harness/run.sh --print bc-ean13    # show the bytes that test produces, as hex
sh tools/escpos-harness/run.sh --jdk <java_home>   # build and run with a particular JDK
sh tools/escpos-harness/run.sh --keep              # keep the temporary build directory
sh tools/escpos-harness/run.sh --help
```

There is deliberately no flag that regenerates the expected-byte files. A test that rewrites its
own expectations proves nothing.

The harness lives under `tools/`, which is not part of the application build. **Nothing in
`tools/` is ever packaged into the shipped jar.**

### 13.2 What the suite proves

Every command sequence the driver emits is compared against a file of expected bytes that was
typed by hand from the published ESC/POS command specification. Those expected-byte files are
never generated from the driver - a test that regenerates its own expectations proves nothing.

Proven byte for byte: the printer start-up block for both profiles; bold, underline and character
size; line handling; all four barcode types and their positioning; logo and image conversion
including banding, width clamping and transparency; every cut and drawer setting; every character
set together with the instruction that selects it; and a complete minimal receipt from start to
cut.

Proven end to end through a real connection: the network transport, against a local listener that
impersonates a printer on port 9100, byte-identical to the file transport for the same receipt;
the file transport; and the behaviour when a network printer refuses the connection - no crash,
and an error message naming the address.

Proven not to crash: every malformed configuration string, which falls back to "no printer" and
lets the application start; and a serial configuration on an Apple Silicon Mac, which produces
the "Serial port unavailable" tab without the serial library ever being loaded.

Also proven end to end: a whole receipt rendered from a real receipt template through the real
template parser to a real file, matching the expected bytes exactly.

### 13.3 What the suite does not prove

Be honest with yourself about this list before rolling out to a shop.

- **No hardware was connected. At all.** No printer, no cash drawer, no customer display, no
  scale, no serial adapter. The bytes are right against the specification; whether a particular
  printer likes them is unverified.
- **`escpos:printer` / `escpos:usb` has never delivered a job to a real queue.** Every step up to
  the final hand-off was exercised, but macOS refuses to create a raw queue so the last step
  could not be. The first Windows or Linux till to use this transport is the real test. Expect to
  need one round of feedback.
- **Serial has not been exercised at all** - no serial adapter was available, and the development
  machine was an Apple Silicon Mac where serial cannot run. Flow control in particular is
  configurable but untested.
- **Barcode physical size is unverified.** The height and width defaults are correct for the
  Epson TM series; on another manufacturer's printer the symbol may come out a different size.
  Adjust `bcheight` and `bcwidth`.
- **Logos in printer memory (`logo=nv:...`) are untested** - storing one requires the vendor's
  utility and the printer.
- **The Rosetta 2 workaround in [7.1](#71-serial-peripherals-do-not-work-on-apple-silicon-m1m2m3m4-macs)
  is guidance, not a tested procedure.** No x86_64 Java was installed to walk it through.
- **Whether a print queue is genuinely RAW cannot be checked** by the POS or by the test suite.
  See [7.3](#73-the-queue-must-be-a-raw-queue-and-the-pos-cannot-check-that-for-you).
