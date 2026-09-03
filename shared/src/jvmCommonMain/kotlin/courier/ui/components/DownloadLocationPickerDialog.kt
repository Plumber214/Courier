package courier.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import courier.platform.getPlatformActions
import courier.ui.theme.AccentCyan
import courier.ui.theme.AccentPink
import courier.ui.theme.CardBorderDark
import courier.ui.theme.GlassBorderGradient
import courier.ui.theme.PrimaryIndigo
import courier.ui.theme.SurfaceCard
import courier.ui.theme.SurfaceDark
import courier.ui.theme.SurfaceVariantDark
import courier.ui.theme.TextMuted
import courier.ui.theme.TextPrimary
import courier.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Chooses a download folder, in the app's own chrome.
 *
 * Desktop used a Swing `JFileChooser` — a system dialog in the platform look
 * and feel, dropped in the middle of a dark Compose app, that could not be
 * themed and blocked on the AWT thread. It is replaced by the browser below.
 *
 * Android keeps the media-root-plus-subfolder form: scoped storage means the
 * app cannot enumerate arbitrary directories, so a browser there would show
 * empty folders and look broken. [PlatformActions.canBrowseFilesystem] decides
 * which of the two is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadLocationPickerDialog(
    onDismissRequest: () -> Unit,
    onLocationSelected: (String) -> Unit
) {
    val platformActions = remember { getPlatformActions() }
    val canBrowse = remember { platformActions.canBrowseFilesystem() }

    Dialog(onDismissRequest = onDismissRequest) {
        BoxWithConstraints {
            val maxDialogHeight = maxHeight * 0.9f

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight)
                    .padding(4.dp),
                shape = RoundedCornerShape(24.dp),
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, GlassBorderGradient)
            ) {
                if (canBrowse) {
                    FolderBrowser(
                        onDismissRequest = onDismissRequest,
                        onLocationSelected = onLocationSelected
                    )
                } else {
                    MediaRootPicker(
                        onDismissRequest = onDismissRequest,
                        onLocationSelected = onLocationSelected
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderBrowser(
    onDismissRequest: () -> Unit,
    onLocationSelected: (String) -> Unit
) {
    val platformActions = remember { getPlatformActions() }
    val roots = remember { platformActions.getStandardMediaRoots() }
    val scope = rememberCoroutineScope()

    var currentPath by remember {
        mutableStateOf(
            roots.firstOrNull() ?: platformActions.getDefaultDownloadDirectory()
        )
    }
    var isProbing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var newFolderName by remember { mutableStateOf<String?>(null) }

    val children = remember(currentPath) { platformActions.listSubdirectories(currentPath) }
    val parent = remember(currentPath) { platformActions.parentDirectory(currentPath) }

    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            text = "Choose Download Folder",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Current location, with the way back out of it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(10.dp))
                .border(1.dp, CardBorderDark, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    parent?.let {
                        currentPath = it
                        errorMessage = null
                        newFolderName = null
                    }
                },
                enabled = parent != null,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Up one folder",
                    tint = if (parent != null) AccentCyan else TextMuted,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = currentPath,
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = { newFolderName = if (newFolderName == null) "" else null },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = "New folder",
                    tint = AccentCyan,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        if (newFolderName != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newFolderName ?: "",
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("New folder name", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = CardBorderDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val name = newFolderName.orEmpty()
                        getPlatformActions().createSubdirectory(currentPath, name).fold(
                            onSuccess = {
                                currentPath = it
                                newFolderName = null
                                errorMessage = null
                            },
                            onFailure = { errorMessage = it.message }
                        )
                    },
                    enabled = !newFolderName.isNullOrBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                ) {
                    Text("Create", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (children.isEmpty()) {
                Text(
                    text = "No subfolders here. Save to this folder, or create one.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                )
            }

            for (child in children) {
                val name = child.replace('\\', '/').trimEnd('/').substringAfterLast('/')
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard, RoundedCornerShape(8.dp))
                        .clickable {
                            currentPath = child
                            errorMessage = null
                            newFolderName = null
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = AccentCyan.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = name.ifBlank { child },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage ?: "",
                color = AccentPink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        PickerActions(
            confirmLabel = "Save here",
            isProbing = isProbing,
            enabled = true,
            onCancel = onDismissRequest,
            onConfirm = {
                scope.launch {
                    isProbing = true
                    errorMessage = null
                    val probe = getPlatformActions().probeDirectoryWritable(currentPath)
                    isProbing = false
                    probe.fold(
                        onSuccess = { onLocationSelected(currentPath) },
                        onFailure = { errorMessage = it.message ?: "This folder is not writable" }
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaRootPicker(
    onDismissRequest: () -> Unit,
    onLocationSelected: (String) -> Unit
) {
    val platformActions = remember { getPlatformActions() }
    val standardRoots = remember { platformActions.getStandardMediaRoots() }
    val defaultRoot = standardRoots.firstOrNull() ?: platformActions.getDefaultDownloadDirectory()

    var selectedRoot by remember { mutableStateOf(defaultRoot) }
    var subfolder by remember { mutableStateOf("Courier") }
    var isProbing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val cleanSubfolder = subfolder.trim().replace("/", "").replace("\\", "").replace("..", "")
    val resolvedPath = if (cleanSubfolder.isBlank()) selectedRoot else "$selectedRoot/$cleanSubfolder"

    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            text = "Add Download Location",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Choose a storage collection and a folder inside it.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "STORAGE",
            color = AccentCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            standardRoots.forEach { rootPath ->
                val rootName = rootPath.replace('\\', '/').trimEnd('/').substringAfterLast('/')
                val isSelected = selectedRoot == rootPath

                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) PrimaryIndigo.copy(alpha = 0.3f) else SurfaceVariantDark,
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (isSelected) AccentCyan else CardBorderDark,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            selectedRoot = rootPath
                            errorMessage = null
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = rootName.ifBlank { rootPath },
                        color = if (isSelected) TextPrimary else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "FOLDER NAME",
            color = AccentCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = subfolder,
            onValueChange = { input ->
                subfolder = input.filter {
                    it != '/' && it != '\\' && it != ':' && it != '*' &&
                        it != '?' && it != '"' && it != '<' && it != '>' && it != '|'
                }
                errorMessage = null
            },
            placeholder = { Text("e.g. Courier, Clips, Instagram", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = CardBorderDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(10.dp))
                .border(1.dp, CardBorderDark, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Text("Full path", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = resolvedPath,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage ?: "",
                color = AccentPink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        PickerActions(
            confirmLabel = "Save location",
            isProbing = isProbing,
            enabled = cleanSubfolder.isNotEmpty(),
            onCancel = onDismissRequest,
            onConfirm = {
                scope.launch {
                    isProbing = true
                    errorMessage = null
                    val probe = platformActions.probeDirectoryWritable(resolvedPath)
                    isProbing = false
                    probe.fold(
                        onSuccess = { onLocationSelected(resolvedPath) },
                        onFailure = { errorMessage = it.message ?: "Failed write probe for this location" }
                    )
                }
            }
        )
    }
}

@Composable
private fun PickerActions(
    confirmLabel: String,
    isProbing: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel, enabled = !isProbing) {
            Text("Cancel", color = TextSecondary, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onConfirm,
            enabled = !isProbing && enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryIndigo,
                contentColor = androidx.compose.ui.graphics.Color.White
            )
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.ui.graphics.Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Named, because the check writes and deletes a probe file and
                // the user should know that is what the wait is.
                Text("Testing write access…", fontSize = 12.sp)
            } else {
                Text(confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
