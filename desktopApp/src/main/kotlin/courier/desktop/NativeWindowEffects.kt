package courier.desktop

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import java.awt.Window

object NativeWindowEffects {

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38

    private const val DWMWCP_ROUND = 2
    private const val DWMSBT_TRANSIENTWINDOW = 3 // Acrylic

    @Structure.FieldOrder("cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight")
    class MARGINS : Structure {
        @JvmField var cxLeftWidth: Int = 0
        @JvmField var cxRightWidth: Int = 0
        @JvmField var cyTopHeight: Int = 0
        @JvmField var cyBottomHeight: Int = 0

        constructor() : super()
        constructor(all: Int) : super() {
            cxLeftWidth = all
            cxRightWidth = all
            cyTopHeight = all
            cyBottomHeight = all
        }
    }

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name", "").lowercase().contains("win")
    }

    private val dwmApi: Function? by lazy {
        if (!isWindows) null
        else try {
            Function.getFunction("dwmapi", "DwmSetWindowAttribute")
        } catch (_: Throwable) {
            null
        }
    }

    private val dwmExtendFrame: Function? by lazy {
        if (!isWindows) null
        else try {
            Function.getFunction("dwmapi", "DwmExtendFrameIntoClientArea")
        } catch (_: Throwable) {
            null
        }
    }

    fun applyWindowEffects(window: Window) {
        if (!isWindows) return
        try {
            val pointer = Native.getWindowPointer(window) ?: return
            val hwnd = HWND(pointer)

            // 1. Enable Immersive Dark Mode for window framing
            try {
                val darkMode = IntByReference(1)
                dwmApi?.invoke(HRESULT::class.java, arrayOf(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, darkMode, 4))
            } catch (_: Throwable) {}

            // 2. Apply Windows 11 True Rounded Corners (DWMWCP_ROUND = 2)
            try {
                val cornerPref = IntByReference(DWMWCP_ROUND)
                dwmApi?.invoke(HRESULT::class.java, arrayOf(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPref, 4))
            } catch (_: Throwable) {}

            // 3. Extend Frame & Apply Transient Acrylic Backdrop (Windows 11 22H2+)
            try {
                val margins = MARGINS(-1)
                dwmExtendFrame?.invoke(HRESULT::class.java, arrayOf(hwnd, margins))

                val backdropType = IntByReference(DWMSBT_TRANSIENTWINDOW)
                dwmApi?.invoke(HRESULT::class.java, arrayOf(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, backdropType, 4))
            } catch (_: Throwable) {}

        } catch (t: Throwable) {
            println("Native window effects not supported or failed: ${t.message}")
        }
    }
}