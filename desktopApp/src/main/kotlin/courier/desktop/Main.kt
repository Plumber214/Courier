package courier.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import courier.ui.App
import courier.ui.theme.CardBorderDark
import courier.ui.theme.GlassBackground
import courier.ui.theme.GlassBorderGradient
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter

fun main() {
    System.setProperty("skiko.renderApi", "DIRECTX_12")

    application {
        val windowState = rememberWindowState(width = 860.dp, height = 900.dp)

        Window(
            onCloseRequest = ::exitApplication,
            title = "Courier",
            state = windowState,
            undecorated = true,
            transparent = true
        ) {
            val isMaximized = windowState.placement == WindowPlacement.Maximized

            // Setup smooth edge resizing on undecorated transparent window
            DisposableEffect(window) {
                window.minimumSize = Dimension(600, 660)

                val resizeBorder = 8
                var resizeDirection = 0
                val NONE = 0
                val N = 1
                val S = 2
                val W = 4
                val E = 8
                val NW = N or W
                val NE = N or E
                val SW = S or W
                val SE = S or E

                var dragStartPoint: Point? = null
                var dragStartBounds: java.awt.Rectangle? = null

                val mouseMotionListener = object : MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent) {
                        if (isMaximized) {
                            window.cursor = Cursor.getDefaultCursor()
                            return
                        }
                        val x = e.x
                        val y = e.y
                        val w = window.width
                        val h = window.height

                        var dir = NONE
                        if (y < resizeBorder) dir = dir or N
                        if (y > h - resizeBorder) dir = dir or S
                        if (x < resizeBorder) dir = dir or W
                        if (x > w - resizeBorder) dir = dir or E

                        resizeDirection = dir

                        window.cursor = when (dir) {
                            NW -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                            NE -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
                            SW -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
                            SE -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                            N -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                            S -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
                            W -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                            E -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                            else -> Cursor.getDefaultCursor()
                        }
                    }

                    override fun mouseDragged(e: MouseEvent) {
                        if (resizeDirection == NONE || dragStartPoint == null || dragStartBounds == null || isMaximized) return

                        val currentScreen = e.locationOnScreen
                        val dx = currentScreen.x - dragStartPoint!!.x
                        val dy = currentScreen.y - dragStartPoint!!.y

                        val orig = dragStartBounds!!
                        var newX = orig.x
                        var newY = orig.y
                        var newW = orig.width
                        var newH = orig.height

                        val minW = window.minimumSize.width
                        val minH = window.minimumSize.height

                        if ((resizeDirection and E) != 0) {
                            newW = (orig.width + dx).coerceAtLeast(minW)
                        }
                        if ((resizeDirection and S) != 0) {
                            newH = (orig.height + dy).coerceAtLeast(minH)
                        }
                        if ((resizeDirection and W) != 0) {
                            val candidateW = orig.width - dx
                            if (candidateW >= minW) {
                                newW = candidateW
                                newX = orig.x + dx
                            }
                        }
                        if ((resizeDirection and N) != 0) {
                            val candidateH = orig.height - dy
                            if (candidateH >= minH) {
                                newH = candidateH
                                newY = orig.y + dy
                            }
                        }

                        window.setBounds(newX, newY, newW, newH)
                        window.validate()
                    }
                }

                val mouseListener = object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (resizeDirection != NONE && !isMaximized) {
                            dragStartPoint = e.locationOnScreen
                            dragStartBounds = window.bounds
                        }
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        dragStartPoint = null
                        dragStartBounds = null
                    }
                }

                window.addMouseListener(mouseListener)
                window.addMouseMotionListener(mouseMotionListener)

                onDispose {
                    window.removeMouseListener(mouseListener)
                    window.removeMouseMotionListener(mouseMotionListener)
                }
            }

            val windowCornerRadius = if (isMaximized) 0.dp else 18.dp

            // Clean, full-bleed Glass Window with crisp glowing perimeter border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(windowCornerRadius))
                    .background(GlassBackground)
                    .border(
                        width = 1.5.dp,
                        brush = GlassBorderGradient,
                        shape = RoundedCornerShape(windowCornerRadius)
                    )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Custom Glass Title Bar
                    WindowDraggableArea {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(Color(0x660B0D18))
                                .padding(start = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Logo & App Name
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(PrimaryIndigo, RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                Spacer(Modifier.width(10.dp))

                                Text(
                                    text = "Courier",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            // Window Controls: Minimize, Maximize, Close
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Minimize
                                Box(
                                    modifier = Modifier
                                        .size(46.dp, 40.dp)
                                        .clickable { windowState.isMinimized = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Minimize,
                                        contentDescription = "Minimize",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                // Maximize / Restore
                                Box(
                                    modifier = Modifier
                                        .size(46.dp, 40.dp)
                                        .clickable {
                                            windowState.placement = if (isMaximized) {
                                                WindowPlacement.Floating
                                            } else {
                                                WindowPlacement.Maximized
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CropSquare,
                                        contentDescription = "Maximize",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }

                                // Close (with red hover state)
                                var isCloseHovered by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .size(46.dp, 40.dp)
                                        .background(if (isCloseHovered) Color(0xFFE81123) else Color.Transparent)
                                        .clickable { exitApplication() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = if (isCloseHovered) Color.White else TextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Frosted Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CardBorderDark)
                    )

                    // Main App Content Viewport
                    Box(modifier = Modifier.weight(1f)) {
                        App()
                    }
                }
            }
        }
    }
}
