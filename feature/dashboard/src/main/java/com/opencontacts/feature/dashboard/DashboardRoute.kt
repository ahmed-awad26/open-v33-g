package com.opencontacts.feature.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencontacts.core.ui.OpenContactsLogo

@Composable
fun DashboardRoute(
    activeVaultName: String,
    vaultCount: Int,
    contactCount: Int,
    onOpenContacts: () -> Unit,
    onOpenVaults: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenImportExport: () -> Unit,
) {
    val actions = listOf(
        DashboardAction("Contacts", "Add, edit, delete, call", Icons.Default.Contacts, onOpenContacts),
        DashboardAction("Search", "Fast lookup inside vault", Icons.Default.ManageSearch, onOpenSearch),
        DashboardAction("Tags & folders", "Organize modern workspaces", Icons.Default.Workspaces, onOpenWorkspace),
        DashboardAction("Vaults", "Switch and secure workspaces", Icons.Default.Security, onOpenVaults),
        DashboardAction("Security", "PIN, biometric, lock state", Icons.Default.AdminPanelSettings, onOpenSecurity),
        DashboardAction("Backup", "Encrypted backups and restore", Icons.Default.Backup, onOpenBackup),
        DashboardAction("Import/Export", "VCF, CSV, JSON workflows", Icons.Default.FolderCopy, onOpenImportExport),
    )

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surface
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val infiniteTransition = rememberInfiniteTransition(label = "hero_shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer_offset",
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── Hero card ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 12.dp, shape = RoundedCornerShape(28.dp), clip = false)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryContainer,
                                primaryContainer.copy(alpha = 0.85f),
                                primary.copy(alpha = 0.18f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(
                                800f * shimmerOffset.coerceIn(0.1f, 0.9f),
                                400f * shimmerOffset.coerceIn(0.1f, 0.9f),
                            ),
                        ),
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .shadow(8.dp, RoundedCornerShape(18.dp))
                                .clip(RoundedCornerShape(18.dp))
                                .background(surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            OpenContactsLogo(modifier = Modifier.size(44.dp), cornerRadius = 12.dp)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "OpenContacts",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.5).sp,
                                ),
                                color = onPrimaryContainer,
                            )
                            Text(
                                "Private vault-first contact manager",
                                style = MaterialTheme.typography.bodyMedium,
                                color = onPrimaryContainer.copy(alpha = 0.72f),
                            )
                        }
                    }

                    HorizontalDivider(color = primary.copy(alpha = 0.15f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HeroStatChip(
                            modifier = Modifier.weight(1f),
                            label = "Active vault",
                            value = activeVaultName,
                            accent = primary,
                            surface = surface,
                        )
                        HeroStatChip(
                            modifier = Modifier.weight(1f),
                            label = "Vaults",
                            value = vaultCount.toString(),
                            accent = primary,
                            surface = surface,
                        )
                        HeroStatChip(
                            modifier = Modifier.weight(1f),
                            label = "Contacts",
                            value = contactCount.toString(),
                            accent = primary,
                            surface = surface,
                        )
                    }
                }
            }

            // ── Section header ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(primary),
                )
                Text(
                    "Workspace",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                    ),
                )
            }

            // ── Action grid ────────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(actions) { action ->
                    ModernActionCard(action = action, primary = primary)
                }
            }
        }
    }
}

@Composable
private fun HeroStatChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color,
    surface: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = surface.copy(alpha = 0.82f),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.8f),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModernActionCard(action: DashboardAction, primary: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = action.onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 3.dp,
            pressedElevation = 8.dp,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    action.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                )
                Text(
                    action.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(primary),
                )
                Text(
                    "Open",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = primary,
                )
            }
        }
    }
}

private data class DashboardAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
