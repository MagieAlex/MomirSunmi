package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;
import android.graphics.Bitmap;

/**
 * Sunmi's built-in printer service (package woyou.aidlservice.jiuiv5).
 *
 * ------------------------------------------------------------------------
 *  READ THIS BEFORE ADDING A METHOD
 * ------------------------------------------------------------------------
 * AIDL assigns Binder transaction codes by *declaration order*, starting at
 * TRANSACTION_first = 1. The codes are not part of the method name, so a
 * client whose method order differs from the service's will silently call the
 * wrong function across the Binder boundary.
 *
 * The published copies of this interface disagree with each other. Comparing
 * three independent public copies (react-native-sunmi-inner-printer,
 * sunmi_printer, SunmiV2-Android-Library) shows the first 21 declarations are
 * byte-for-byte identical everywhere, and everything after index 20 diverges
 * between SDK generations - one copy has commitPrint at 21, another has
 * commitPrinterBuffer, a third has enterPrinterBuffer.
 *
 * So this file deliberately stops at index 20. Everything MomirSunmi needs
 * lives in that stable prefix, and the app drives the printer through
 * sendRAWData (index 10) with ESC/POS, which sidesteps the divergent buffer
 * and style calls entirely.
 *
 * Do not reorder. Do not insert. Append only, and only if you have verified
 * the target firmware. See docs/sunmi-aidl.md.
 * ------------------------------------------------------------------------
 */
interface IWoyouService {

    /* 0 */  boolean postPrintData(String packageName, in byte[] data, int offset, int length);
    /* 1 */  int getFirmwareStatus();
    /* 2 */  String getServiceVersion();
    /* 3 */  void printerInit(in ICallback callback);
    /* 4 */  void printerSelfChecking(in ICallback callback);
    /* 5 */  String getPrinterSerialNo();
    /* 6 */  String getPrinterVersion();
    /* 7 */  String getPrinterModal();
    /* 8 */  void getPrintedLength(in ICallback callback);
    /* 9 */  void lineWrap(int n, in ICallback callback);
    /* 10 */ void sendRAWData(in byte[] data, in ICallback callback);
    /* 11 */ void setAlignment(int alignment, in ICallback callback);
    /* 12 */ void setFontName(String typeface, in ICallback callback);
    /* 13 */ void setFontSize(float fontsize, in ICallback callback);
    /* 14 */ void printText(String text, in ICallback callback);
    /* 15 */ void printTextWithFont(String text, String typeface, float fontsize, in ICallback callback);
    /* 16 */ void printColumnsText(in String[] colsTextArr, in int[] colsWidthArr, in int[] colsAlign, in ICallback callback);
    /* 17 */ void printBitmap(in Bitmap bitmap, in ICallback callback);
    /* 18 */ void printBarCode(String data, int symbology, int height, int width, int textposition, in ICallback callback);
    /* 19 */ void printQRCode(String data, int modulesize, int errorlevel, in ICallback callback);
    /* 20 */ void printOriginalText(String text, in ICallback callback);
}
