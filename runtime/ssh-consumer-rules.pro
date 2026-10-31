# JSch selects cryptographic implementations by class name (also on Android).
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }

# Optional desktop integrations bundled in JSch. Tianyan uses password/publickey
# authentication, without Pageant, Kerberos, junixsocket or Log4j2 adapters.
# Match the absent optional types precisely; SSH/crypto missing classes still fail R8.
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.platform.win32.BaseTSD$ULONG_PTR
-dontwarn com.sun.jna.platform.win32.Kernel32
-dontwarn com.sun.jna.platform.win32.User32
-dontwarn com.sun.jna.platform.win32.WinBase$SECURITY_ATTRIBUTES
-dontwarn com.sun.jna.platform.win32.WinBase
-dontwarn com.sun.jna.platform.win32.WinDef$HWND
-dontwarn com.sun.jna.platform.win32.WinDef$LPARAM
-dontwarn com.sun.jna.platform.win32.WinDef$LRESULT
-dontwarn com.sun.jna.platform.win32.WinDef$WPARAM
-dontwarn com.sun.jna.platform.win32.WinNT$HANDLE
-dontwarn com.sun.jna.platform.win32.WinUser$COPYDATASTRUCT
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid
-dontwarn org.newsclub.net.unix.AFUNIXServerSocketChannel
-dontwarn org.newsclub.net.unix.AFUNIXSocketAddress
-dontwarn org.newsclub.net.unix.AFUNIXSocketChannel
