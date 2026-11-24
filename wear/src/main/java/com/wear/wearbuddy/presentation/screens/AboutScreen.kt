package com.wear.wearbuddy.presentation.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.BuildConfig
import com.wear.wearbuddy.R
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberCopyText

@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val appIcon = remember(context) {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(160, 160).asImageBitmap()
    }
    val listState = rememberTransformingLazyColumnState()
    val uriHandler = LocalUriHandler.current
    val copy = rememberCopyText()
    val repository = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}"

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        edgeButton = {
            EdgeButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.back, maxLines = 1)
            }
        }
    ) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = padding + PaddingValues(start = 12.dp, end = 12.dp, top = 26.dp)
        ) {
            item(key = "app") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(s.appName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal)
                    Text(
                        Build.MODEL,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item(key = "introduction") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 13.dp, vertical = 12.dp)
                ) {
                    Text(s.appIntroTitle, style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(s.appIntro, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(s.appLicense, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(s.sourceRepository, style = MaterialTheme.typography.labelSmall)
                    Text(
                        repository,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                try {
                                    uriHandler.openUri(repository)
                                } catch (_: IllegalArgumentException) {
                                    copy(repository)
                                }
                            },
                            onLongClick = { copy(repository) },
                            onLongClickLabel = s.copyTextLabel
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline)
                    )
                }
            }
            item(key = "developers-header") {
                ListHeader { Text(s.developerList) }
            }
            item(key = "developer-oride") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.oride_avatar),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(34.dp).clip(CircleShape)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Oride", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(s.developerRole, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
