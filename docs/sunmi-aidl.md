# The Sunmi printer AIDL

Why `app/src/main/aidl/woyou/aidlservice/jiuiv5/IWoyouService.aidl` stops at 21
methods, and why you should not casually add a 22nd.

## The problem

Sunmi's built-in printer is reached by binding to a system service:

```
package  woyou.aidlservice.jiuiv5
action   woyou.aidlservice.jiuiv5.IWoyouService
```

Sunmi does not ship this interface through a package repository. Everybody
copies an `.aidl` file from somewhere, and **the copies in circulation disagree
with each other**.

That would be harmless if AIDL matched methods by name. It does not. `aidl`
assigns Binder transaction codes by **declaration order**, starting at
`TRANSACTION_first = 1`:

```java
static final int TRANSACTION_printerInit = IBinder.FIRST_CALL_TRANSACTION + 3;
static final int TRANSACTION_sendRAWData = IBinder.FIRST_CALL_TRANSACTION + 10;
```

The name exists only in your generated stub. What crosses the Binder boundary is
an integer. If your declaration order differs from the service's, you call a
different function than the one you wrote — with your arguments marshalled for
the wrong signature.

## What the copies actually disagree about

Three independent public copies were compared method by method:

- `januslo/react-native-sunmi-inner-printer`
- `brasizza/sunmi_printer`
- `FelOrtiz/SunmiV2-Android-Library`

They contain 39, 50 and 32 methods respectively. Indices **0 through 20 are
identical in all three**. From index 21 they diverge immediately:

| Index | januslo | brasizza | FelOrtiz |
|---:|---|---|---|
| 20 | `printOriginalText` | `printOriginalText` | `printOriginalText` |
| 21 | `commitPrint` | `commitPrinterBuffer` | `commitPrinterBuffer` |
| 22 | `commitPrinterBuffer` | `cutPaper` | `enterPrinterBuffer` |
| 23 | `enterPrinterBuffer` | `getCutPaperTimes` | `exitPrinterBuffer` |
| 24 | `exitPrinterBuffer` | `openDrawer` | `printColumnsString` |

Three different functions at transaction 22. Pick the wrong file and
`enterPrinterBuffer()` opens a cash drawer.

There is one disagreement inside the stable prefix, at index 8:

```
januslo   void getPrintedLength(in ICallback callback)
brasizza  int  getPrintedLength()
FelOrtiz  void getPrintedLength(in ICallback callback)
```

Return type and arguments do not affect the transaction code, only the
marshalling, so the two-of-three majority is declared and the method is simply
never called.

## What this project does

The AIDL file stops at index 20. Everything MomirSunmi needs is inside the
prefix that all three copies agree on:

```
 2  String getServiceVersion()
 3  void   printerInit(in ICallback callback)
 5  String getPrinterSerialNo()
 6  String getPrinterVersion()
 7  String getPrinterModal()
10  void   sendRAWData(in byte[] data, in ICallback callback)
11  void   setAlignment(int alignment, in ICallback callback)
```

`sendRAWData` at index 10 is the important one. Driving the printer with ESC/POS
through a single raw-data call sidesteps the entire divergent region — the
buffer management, the style setters, the cutter, the drawer — none of which
this project needs anyway.

That was not only a compatibility decision. `printBitmap` re-binarises whatever
it is handed, which would undo the dithering, and `printText` would have to
round-trip card names through an ESC/POS code page. See
[printing.md](printing.md).

## Verifying it on your device

Settings shows the service's own identity strings, read back over the same
interface:

```
service   1.1.30
model     V2
firmware  …
serial    …
status    0
```

This doubles as a check on the transaction mapping. Those five values come from
indices 2, 5, 6, 7 and 1. If the mapping were off, they would throw or return
nonsense rather than a version and a serial.

## The callback

`ICallback` goes the other way — the service calls into *your* stub — so the
risk is reversed. Declaring one method more than the firmware knows about is
harmless (it never fires); declaring one fewer breaks the mapping.

Two of the three copies have four methods, one has three. Four are declared.

## If you need a method past index 20

1. Work out which SDK generation your firmware is, from `getServiceVersion()`.
2. Find an AIDL copy that matches that generation, not merely one that compiles.
3. Verify against a method with an observable, harmless effect — `lineWrap`
   feeds paper — before trusting anything destructive.
4. Consider whether ESC/POS can do it instead. It usually can.

Do not reorder the existing declarations. Append only.
