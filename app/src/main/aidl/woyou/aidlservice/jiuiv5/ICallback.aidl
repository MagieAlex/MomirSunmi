package woyou.aidlservice.jiuiv5;

/**
 * Result callback handed to IWoyouService. The service calls into our stub, so
 * declaring one method more than the firmware knows about is harmless (it just
 * never fires) while declaring one fewer would break the transaction mapping.
 * onPrintResult appears in two of the three public copies, so it stays.
 */
interface ICallback {
    void onRunResult(boolean isSuccess);
    void onReturnString(String result);
    void onRaiseException(int code, String msg);
    void onPrintResult(int code, String msg);
}
