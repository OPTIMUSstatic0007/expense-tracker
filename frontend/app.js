const API_BASE_URL = '/transactions';
const EXPORT_BASE_URL = '/export';
const BACKUP_BASE_URL = '/backup';

// State Management
let transactions = [];
let editingId = null;
let currentPage = 1;
let hasMore = false;
let isLoadingMore = false;


// ═══════════════════════════════════════════════════════════════════
// THEME MANAGEMENT
// Single source of truth: AndroidBridge (SharedPreferences) when
// running inside the Android WebView; falls back to localStorage
// for desktop/browser preview.
// ═══════════════════════════════════════════════════════════════════

/**
 * Apply a theme to the page. Called from:
 * 1. Android via evaluateJavascript("applyTheme('dark')")
 * 2. initTheme() on page load
 * 3. toggleTheme() on drawer button click
 */
function applyTheme(mode) {
    if (mode === 'dark') {
        document.documentElement.setAttribute('data-theme', 'dark');
        localStorage.setItem('theme', 'dark');
    } else {
        document.documentElement.removeAttribute('data-theme');
        localStorage.setItem('theme', 'light');
    }
    // Refresh to pick up new theme colors in charts
    if (typeof refreshData === 'function') {
        refreshData();
    }
}

function initTheme() {
    // When running inside Android WebView, ask the bridge for the
    // persisted theme (SharedPreferences) so we stay in sync.
    if (window.AndroidBridge && typeof window.AndroidBridge.getTheme === 'function') {
        try {
            var bridgeTheme = window.AndroidBridge.getTheme();
            applyTheme(bridgeTheme);
            return;
        } catch (e) {
            console.warn('AndroidBridge.getTheme() failed, falling back to localStorage', e);
        }
    }
    // Fallback: localStorage (desktop/browser)
    var savedTheme = localStorage.getItem('theme');
    var prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    if (savedTheme === 'dark' || (!savedTheme && prefersDark)) {
        applyTheme('dark');
    } else {
        applyTheme('light');
    }
}

function toggleTheme() {
    var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    var nextMode = isDark ? 'light' : 'dark';
    
    // 1. Optimistic UI update for instant feedback
    applyTheme(nextMode);
    
    // 2. Sync state with native Android app
    if (window.AndroidBridge && window.AndroidBridge.setTheme) {
        try {
            window.AndroidBridge.setTheme(nextMode);
        } catch (e) {
            console.warn('AndroidBridge.setTheme() failed', e);
        }
    }
}

initTheme();

// DOM Elements
const form = document.getElementById('exp-form');
const historyBody = document.getElementById('history-body');
const recordCountEl = document.getElementById('record-count');
const saveBtn = document.getElementById('save-btn');
const emptyStateEl = document.getElementById('empty-state');
const toastContainer = document.getElementById('toast-container');
const loadMoreBtn = document.getElementById('load-more-btn');
const loadMoreContainer = document.getElementById('load-more-container');

// Desktop Modal Elements (overlay — untouched for ≥769px)
const modalWrapper   = document.getElementById('transaction-modal');
const modalBackdrop  = document.getElementById('modal-backdrop');
const modalClose     = document.getElementById('modal-close');
const modalTitle     = document.getElementById('modal-title');
const fabAdd         = document.getElementById('fab-add');
const saveBtnText    = document.getElementById('save-btn-text');

// Mobile Inline Form Elements (document-flow, no overlay)
const mobileWrapper  = document.getElementById('mobile-inline-form');
const mobileBody     = mobileWrapper ? mobileWrapper.querySelector('.mobile-inline-body') : null;
const mobileClose    = document.getElementById('mobile-form-close');
const mobileTitle    = document.getElementById('mobile-form-title');

// Navigation Drawer Elements (mobile only)
const hamburgerBtn     = document.getElementById('hamburger-btn');
const navDrawer        = document.getElementById('nav-drawer');
const navDrawerScrim   = document.getElementById('nav-drawer-scrim');
const navDrawerClose   = document.getElementById('nav-drawer-close');

/** True when the current viewport is mobile (≤768px). Evaluated at call-time. */
function isMobile() { return window.innerWidth <= 768; }

// Management Elements
const backupBtn = document.getElementById('backup-db-btn');
const restoreBtn = document.getElementById('restore-db-btn');
const snapshotBtn = document.getElementById('snapshot-db-btn');
const restoreInput = document.getElementById('restore-input');
const backupStatusEl = document.getElementById('backup-status');
const availableBackupsSelect = document.getElementById('available-backups-select');
const doRestoreSelectedBtn = document.getElementById('do-restore-selected-btn');
const lastBackupTimeEl = document.getElementById('last-backup-time');
const autoBackupCountEl = document.getElementById('auto-backup-count');
const manualBackupCountEl = document.getElementById('manual-backup-count');

// Summary Elements
const balanceEl = document.getElementById('balance');
const creditEl = document.getElementById('total-credit');
const debitEl = document.getElementById('total-debit');
const monthBalanceEl = document.getElementById('month-balance');
const currentViewLabel = document.getElementById('current-view-label');

// Filter Elements
const searchInput = document.getElementById('search-input');
const filterMonth = document.getElementById('filter-month');
const filterYear = document.getElementById('filter-year');
const filterCategory = document.getElementById('filter-category');
const filterType = document.getElementById('filter-type');

// Export Buttons
const exportExcelBtn = document.getElementById('export-excel-btn');
const exportCsvBtn = document.getElementById('export-csv-btn');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    const dateInput = document.getElementById('date');
    if (dateInput) dateInput.valueAsDate = new Date();

    loadTransactions();
    updateBackupStatus();
    setupEventListeners();
});

function setupEventListeners() {
    // Filter Listeners
    [searchInput, filterMonth, filterYear, filterCategory, filterType].forEach(el => {
        if (el) el.addEventListener('input', () => {
            currentPage = 1; // Reset to page 1 on filter
            loadTransactions(false);
        });
    });

    // Resize Handler for Mobile View
    window.addEventListener('resize', () => {
        const isMobileNow = window.innerWidth <= 768;
        if (window.lastWasMobile !== isMobileNow) {
            window.lastWasMobile = isMobileNow;
            applyFilters();
        }
    });

    // Export Handlers (desktop — always bound, buttons exist in DOM)
    if (exportExcelBtn) exportExcelBtn.addEventListener('click', () => triggerExport('excel', exportExcelBtn));
    if (exportCsvBtn) exportCsvBtn.addEventListener('click', () => triggerExport('csv', exportCsvBtn));

    // ═══ MOBILE OVERFLOW MENU ═══
    const overflowBtn = document.getElementById('overflow-menu-btn');
    const overflowDropdown = document.getElementById('overflow-dropdown');

    if (overflowBtn && overflowDropdown) {
        // Toggle dropdown on ⋮ tap
        overflowBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const isOpen = overflowDropdown.classList.toggle('is-open');
            overflowBtn.setAttribute('aria-expanded', isOpen);
            overflowDropdown.setAttribute('aria-hidden', !isOpen);
        });

        // Menu item clicks — reuse existing triggerExport()
        overflowDropdown.querySelectorAll('.overflow-item').forEach(item => {
            item.addEventListener('click', () => {
                const format = item.dataset.export;
                const originalBtn = format === 'excel' ? exportExcelBtn : exportCsvBtn;
                triggerExport(format, originalBtn);
                // Close menu after action
                overflowDropdown.classList.remove('is-open');
                overflowBtn.setAttribute('aria-expanded', 'false');
                overflowDropdown.setAttribute('aria-hidden', 'true');
            });
        });

        // Click outside closes dropdown
        document.addEventListener('click', (e) => {
            if (!overflowDropdown.classList.contains('is-open')) return;
            if (!overflowBtn.contains(e.target) && !overflowDropdown.contains(e.target)) {
                overflowDropdown.classList.remove('is-open');
                overflowBtn.setAttribute('aria-expanded', 'false');
                overflowDropdown.setAttribute('aria-hidden', 'true');
            }
        });
    }

    // Backup & Restore
    if (snapshotBtn) snapshotBtn.addEventListener('click', async () => {
        console.log('[SNAPSHOT] desktop snapshotBtn click received');
        console.log('[SNAPSHOT] current page before backup:', window.location.href);
        setLoading(snapshotBtn, true, 'Create Restore Point');
        if (backupStatusEl) {
            backupStatusEl.innerText = 'Creating Snapshot';
            backupStatusEl.className = 'status-working';
        }
        try {
            if (window.AndroidBridge && typeof window.AndroidBridge.backupDatabase === 'function') {
                console.log('[SNAPSHOT] desktop: calling AndroidBridge.backupDatabase()');
                const responseJson = window.AndroidBridge.backupDatabase();
                console.log('[SNAPSHOT] desktop: raw response:', responseJson);
                const response = JSON.parse(responseJson);
                console.log('[SNAPSHOT] desktop: parsed response:', JSON.stringify(response));
                if (response.status === 'success') {
                    if (backupStatusEl) {
                        backupStatusEl.innerText = 'Restore Point Created';
                        backupStatusEl.className = 'status-manual';
                    }
                    showToast(response.message || 'Manual restore point created');
                    if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics();
                    if (typeof refreshAvailableBackups === 'function') refreshAvailableBackups();
                } else {
                    showToast(response.message || 'Failed to create snapshot', 'error');
                    if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics();
                }
            } else {
                console.log('[SNAPSHOT] desktop: no AndroidBridge, using fetch');
                const response = await fetch(`${BACKUP_BASE_URL}/snapshot`, { method: 'POST' });
                if (response.ok) {
                    if (backupStatusEl) {
                        backupStatusEl.innerText = 'Restore Point Created';
                        backupStatusEl.className = 'status-manual';
                    }
                    showToast('Manual restore point created');
                    if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics();
                } else {
                    showToast('Failed to create snapshot', 'error');
                    if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics();
                }
            }
        } catch (e) {
            console.error('[SNAPSHOT] desktop: exception:', e);
            showToast('Network error or exception', 'error');
            if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics();
        } finally {
            console.log('[SNAPSHOT] desktop: finally block, page is:', window.location.href);
            setLoading(snapshotBtn, false, 'Create Restore Point');
        }
    });

    if (backupBtn) backupBtn.addEventListener('click', () => {
        console.log('[BACKUP] desktop backupBtn click received');
        console.log('[BACKUP] WARNING: this handler does window.location.href redirect!');
        showToast('Preparing database backup...', 'info');
        window.location.href = `${BACKUP_BASE_URL}/database`;
        setTimeout(() => { if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics(); }, 2000);
    });

    // FAB — branches on viewport: inline form on mobile, overlay modal on desktop
    if (fabAdd) fabAdd.addEventListener('click', () => {
        if (isMobile()) {
            toggleMobileForm();
        } else {
            openModal('create');
        }
    });

    // Desktop modal close (overlay path — unchanged)
    if (modalClose)    modalClose.addEventListener('click', () => closeModal());
    if (modalBackdrop) modalBackdrop.addEventListener('click', () => closeModal());

    // Mobile inline form close button
    if (mobileClose) mobileClose.addEventListener('click', () => closeMobileForm());

    // ESC only closes desktop modal; mobile form uses in-flow close button
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !isMobile() && modalWrapper && !modalWrapper.classList.contains('hidden')) {
            closeModal();
        }
    });

    // Real-time validation
    if (form) {
        form.querySelectorAll('input, select').forEach(input => {
            input.addEventListener('blur', () => validateField(input));
        });
    }

    // Pagination Handler
    if (loadMoreBtn) {
        loadMoreBtn.addEventListener('click', () => {
            if (!isLoadingMore && hasMore) {
                currentPage++;
                loadTransactions(true);
            }
        });
    }

    // Navigation Drawer (mobile)
    setupNavDrawer();
}

// ═══════════════════════════════════════════════════════════════════
// MOBILE INLINE FORM — document-flow, no overlay, no body scroll lock
// ═══════════════════════════════════════════════════════════════════

/**
 * Moves #exp-form into the mobile inline card body (once; no duplication).
 * On desktop, returns the form back to the desktop modal section.
 */
function ensureFormIn(target) {
    const expForm = document.getElementById('exp-form');
    if (!target || !expForm) return;
    if (!target.contains(expForm)) target.appendChild(expForm);
}

/** Fills form fields for create or edit mode. */
function populateForm(mode, data) {
    if (mode === 'edit' && data) {
        let formattedDate = data.date;
        if (formattedDate && formattedDate.includes('/')) {
            const [day, month, year] = formattedDate.split('/');
            formattedDate = `${year}-${month}-${day}`;
        }
        document.getElementById('date').value     = formattedDate;
        document.getElementById('type').value     = data.entryType;
        document.getElementById('amount').value   = data.amount;
        document.getElementById('category').value = data.category;
        document.getElementById('source').value   = data.expenseType;
        document.getElementById('person').value   = data.paidTo;
        document.getElementById('notes').value    = data.notes;
        editingId = data.id;
    } else {
        resetForm();
    }
}

function openMobileForm(mode, data = null) {
    if (!mobileWrapper || !mobileBody) return;

    // Move form DOM node into inline card — zero duplication
    ensureFormIn(mobileBody);
    populateForm(mode, data);

    if (mobileTitle)  mobileTitle.innerText  = mode === 'edit' ? 'Edit Entry' : 'Record New Entry';
    if (saveBtnText)  saveBtnText.innerText  = mode === 'edit' ? 'Update Ledger Entry' : 'Save to Ledger';

    mobileWrapper.classList.add('is-open');
    mobileWrapper.setAttribute('aria-hidden', 'false');
    if (fabAdd) fabAdd.classList.add('fab-form-open');

    // Scroll inline form into view after transition begins
    setTimeout(() => mobileWrapper.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 60);
}

function closeMobileForm() {
    if (!mobileWrapper) return;
    mobileWrapper.classList.remove('is-open');
    mobileWrapper.setAttribute('aria-hidden', 'true');
    if (fabAdd) fabAdd.classList.remove('fab-form-open');
    resetForm();
}

function toggleMobileForm(mode = 'create', data = null) {
    if (!mobileWrapper) return;
    if (mobileWrapper.classList.contains('is-open')) {
        closeMobileForm();
    } else {
        openMobileForm(mode, data);
    }
}

// ═══════════════════════════════════════════════════════════════════
// NAVIGATION DRAWER — left-side slide, mobile only
// ═══════════════════════════════════════════════════════════════════

// Drawer status element refs (resolved lazily to avoid null on load)
let _drawerStatusEls = null;
function getDrawerStatusEls() {
    if (!_drawerStatusEls) {
        _drawerStatusEls = {
            appVersion:  document.getElementById('drawer-app-version'),
            syncStatus:  document.getElementById('drawer-sync-status'),
        };
    }
    return _drawerStatusEls;
}

// Client-side mocked state for values with no backend API
let lastExportTime = null;

function setupNavDrawer() {
    if (!hamburgerBtn || !navDrawer || !navDrawerScrim) return;


    // Settings link → open native Settings/Profile screen
    const drawerSettingsLink = document.getElementById('drawer-settings-link');
    if (drawerSettingsLink) {
        drawerSettingsLink.addEventListener('click', () => {
            closeNavDrawer();
            if (window.AndroidBridge && typeof window.AndroidBridge.openSettings === 'function') {
                window.AndroidBridge.openSettings();
            }
        });
    }

    const themeToggleBtn = document.getElementById('theme-toggle-btn');
    if (themeToggleBtn) {
        themeToggleBtn.addEventListener('click', () => {
            toggleTheme();
        });
    }

    // Hamburger tap → open drawer
    hamburgerBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        openNavDrawer();
    });

    // Close button inside drawer
    if (navDrawerClose) {
        navDrawerClose.addEventListener('click', () => closeNavDrawer());
    }

    // Scrim tap → close
    navDrawerScrim.addEventListener('click', () => closeNavDrawer());

    // ═══ DATABASE ACTION BUTTONS (now inside DB Center panel) ═══

    // 1. Create Restore Point — reuses existing /backup/snapshot API
    const drawerSnapshotBtn = document.getElementById('drawer-snapshot-btn');
    if (drawerSnapshotBtn) {
        drawerSnapshotBtn.addEventListener('click', async (e) => {
            e.preventDefault();
            e.stopPropagation();
            console.log('[SNAPSHOT] drawer-snapshot-btn click received');
            console.log('[SNAPSHOT] current page:', window.location.href);
            console.log('[SNAPSHOT] event target:', e.target.id || e.target.className);
            console.log('[SNAPSHOT] db-center-panel open?', document.getElementById('db-center-panel')?.classList.contains('is-open'));
            const textEl  = drawerSnapshotBtn.querySelector('.db-center-action-text');
            const loader  = drawerSnapshotBtn.querySelector('.drawer-action-loader');
            const origText = 'Create Restore Point';

            drawerSnapshotBtn.disabled = true;
            if (textEl) textEl.innerText = 'Creating...';
            if (loader) loader.classList.remove('hidden');

            try {
                if (window.AndroidBridge && typeof window.AndroidBridge.backupDatabase === 'function') {
                    console.log('[SNAPSHOT] calling AndroidBridge.backupDatabase()');
                    const responseStr = window.AndroidBridge.backupDatabase();
                    console.log('[SNAPSHOT] raw response:', responseStr);
                    const response = JSON.parse(responseStr);
                    console.log('[SNAPSHOT] parsed response:', JSON.stringify(response));
                    console.log('[SNAPSHOT] backup completed, status:', response.status);
                    if (response.status === 'success') {
                        console.log('[SNAPSHOT] calling showToast with message:', response.message);
                        showToast(response.message || 'Restore point created', 'success');
                        console.log('[SNAPSHOT] calling updateBackupStatus()');
                        updateBackupStatus();
                        console.log('[SNAPSHOT] calling fetchDbStats()');
                        fetchDbStats();
                        console.log('[SNAPSHOT] post-success page:', window.location.href);
                    } else {
                        console.log('[SNAPSHOT] backup failed:', response.message);
                        showToast(response.message || 'Failed to create snapshot', 'error');
                    }
                } else {
                    console.log('[SNAPSHOT] no AndroidBridge, using fetch');
                    const response = await fetch(`${BACKUP_BASE_URL}/snapshot`, { method: 'POST' });
                    if (response.ok) {
                        showToast('Restore point created');
                        updateBackupStatus();
                        fetchDbStats();
                    } else {
                        showToast('Failed to create restore point', 'error');
                    }
                }
            } catch (e) {
                console.error('[SNAPSHOT] exception:', e);
                showToast('Network error or exception', 'error');
            } finally {
                console.log('[SNAPSHOT] finally block, page is:', window.location.href);
                console.log('[SNAPSHOT] db-center-panel still open?', document.getElementById('db-center-panel')?.classList.contains('is-open'));
                drawerSnapshotBtn.disabled = false;
                if (textEl) textEl.innerText = origText;
                if (loader) loader.classList.add('hidden');
            }
        });
    }

    // 2. Backup Database — using internal AndroidBridge BackupManager
    const drawerBackupBtn = document.getElementById('drawer-backup-btn');
    if (drawerBackupBtn) {
        drawerBackupBtn.addEventListener('click', () => {
            if (window.AndroidBridge && typeof window.AndroidBridge.backupDatabase === 'function') {
                if (confirm('Create a new manual backup of the database?')) {
                    try {
                        const responseStr = window.AndroidBridge.backupDatabase();
                        const response = JSON.parse(responseStr);
                        if (response.status === 'success') {
                            showToast(response.message || 'Database backup created successfully', 'success');
                            refreshBackupMetrics();
                        } else {
                            showToast('Backup failed: ' + response.message, 'error');
                        }
                    } catch (e) {
                        showToast('Error creating backup: ' + e, 'error');
                    }
                }
            } else {
                showToast('Preparing database backup...', 'info');
                window.location.href = `${BACKUP_BASE_URL}/database`;
                setTimeout(() => {
                    updateBackupStatus();
                    fetchDbStats();
                }, 2000);
            }
        });
    }

    // Refresh Backup Metrics functionality
    window.refreshBackupMetrics = function() {
        console.log('[SNAPSHOT] refreshBackupMetrics called');
        console.log('[SNAPSHOT] isMobile (raw ref, no parens):', isMobile);
        console.log('[SNAPSHOT] isMobile() (called):', isMobile());
        console.log('[SNAPSHOT] typeof isMobile:', typeof isMobile);
        console.log('[SNAPSHOT] AndroidBridge present:', !!window.AndroidBridge);
        console.log('[SNAPSHOT] getBackupStatus present:', !!(window.AndroidBridge && window.AndroidBridge.getBackupStatus));
        if (isMobile() && window.AndroidBridge && window.AndroidBridge.getBackupStatus) {
            console.log('[SNAPSHOT] refreshBackupMetrics: entered isMobile branch (parens added)');
            try {
                const statusStr = window.AndroidBridge.getBackupStatus();
                console.log('[SNAPSHOT] getBackupStatus raw:', statusStr);
                const status = JSON.parse(statusStr);
                console.log('[SNAPSHOT] getBackupStatus parsed:', JSON.stringify(status));

                const lastBackupEl = document.getElementById('dbcenter-last-backup');
                const totalBackupsEl = document.getElementById('dbcenter-total-backups');
                const dbSizeEl = document.getElementById('dbcenter-db-size');

                if (lastBackupEl) lastBackupEl.textContent = status.latestBackupTime || '—';
                if (totalBackupsEl) totalBackupsEl.textContent = status.backupCount !== undefined ? status.backupCount : '—';
                if (dbSizeEl && status.latestBackupSize) dbSizeEl.textContent = status.latestBackupSize;

            } catch (e) {
                console.error('[SNAPSHOT] refreshBackupMetrics error:', e);
            }
        } else {
            console.log('[SNAPSHOT] refreshBackupMetrics: skipped (condition false)');
        }
    }

    // 3. Restore Database — mobile restore UI in DB Center panel
    const drawerRestoreBtn = document.getElementById('drawer-restore-btn');
    console.log('[RESTORE] drawer-restore-btn element found:', !!drawerRestoreBtn);
    if (drawerRestoreBtn) {
        let selectedBackupFileName = null;
        let backupsData = [];
        let showingCount = 5;

        drawerRestoreBtn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            console.log('[RESTORE_UI] drawer-restore-btn clicked');

            let ui = document.getElementById('mobile-restore-ui');
            if (!ui) {
                ui = document.createElement('div');
                ui.id = 'mobile-restore-ui';
                ui.style.cssText = 'margin-top: 16px; padding: 16px; background: var(--background); border-radius: 12px; border: 1px solid var(--border); display: none;';
                ui.innerHTML = `
                    <h4 style="margin-bottom: 14px; font-size: 0.9rem; font-weight: 700; color: var(--text-primary);">Select Backup to Restore</h4>
                    <div id="backup-list-container" style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 14px; max-height: 600px; overflow-y: auto; -webkit-overflow-scrolling: touch;"></div>
                    <button id="show-more-backups-btn" class="btn-outline" style="position: relative !important; width: 100%; padding: 10px; font-size: 0.8rem; margin-bottom: 12px; display: none; bottom: auto; border-radius: 8px;">Show More</button>
                    <button id="mobile-do-restore-btn" class="btn-primary" style="position: relative !important; width: 100%; padding: 12px; font-size: 0.875rem; bottom: auto; margin-bottom: 0; border-radius: 8px; opacity: 0.5;" disabled>
                        <span class="btn-text">Restore Selected</span>
                        <span class="btn-loader hidden" style="margin-left: 8px;"></span>
                    </button>
                `;
                // Append outside the .db-center-card to avoid overflow:hidden clipping
                const operationsCard = drawerRestoreBtn.closest('.db-center-card');
                if (operationsCard && operationsCard.parentElement) {
                    operationsCard.parentElement.insertBefore(ui, operationsCard.nextSibling);
                } else {
                    drawerRestoreBtn.parentElement.appendChild(ui);
                }

                document.getElementById('show-more-backups-btn').addEventListener('click', () => {
                    console.log('[RESTORE_UI] show more clicked, expanding to 15');
                    showingCount = 15;
                    renderBackups();
                });

                document.getElementById('mobile-do-restore-btn').addEventListener('click', () => {
                    if (!selectedBackupFileName) return;
                    console.log('[RESTORE_UI] restore requested=' + selectedBackupFileName);

                    // Custom confirmation dialog
                    if (!confirm(
                        'Restore backup\n\n' +
                        selectedBackupFileName + '\n\n' +
                        'Current database will be replaced.\n' +
                        'An emergency backup will be created first.'
                    )) {
                        console.log('[RESTORE_UI] user cancelled restore');
                        return;
                    }

                    if (window.AndroidBridge && typeof window.AndroidBridge.restoreDatabase === 'function') {
                        const btn = document.getElementById('mobile-do-restore-btn');
                        const textSpan = btn.querySelector('.btn-text');
                        const loader = btn.querySelector('.btn-loader');
                        textSpan.innerText = 'Restoring...';
                        loader.classList.remove('hidden');
                        btn.disabled = true;
                        btn.style.opacity = '0.5';

                        setTimeout(() => {
                            try {
                                console.log('[RESTORE_UI] calling AndroidBridge.restoreDatabase(' + selectedBackupFileName + ')');
                                const responseJson = window.AndroidBridge.restoreDatabase(selectedBackupFileName);
                                console.log('[RESTORE_UI] restore response raw:', responseJson);
                                const response = JSON.parse(responseJson);
                                console.log('[RESTORE_UI] restore response parsed:', JSON.stringify(response));

                                if (response.status === 'success') {
                                    console.log('[RESTORE_UI] restore completed');
                                    showToast('Database restored successfully! Reloading...', 'success');
                                    // Refresh metrics and ledger before reload
                                    if (typeof window.refreshBackupMetrics === 'function') window.refreshBackupMetrics();
                                    if (typeof fetchDbStats === 'function') fetchDbStats();
                                    setTimeout(() => window.location.reload(), 1500);
                                } else {
                                    console.log('[RESTORE_UI] restore failed:', response.message);
                                    showToast('Restore failed: ' + (response.message || 'Unknown error'), 'error');
                                    textSpan.innerText = 'Restore Selected';
                                    loader.classList.add('hidden');
                                    btn.disabled = false;
                                    btn.style.opacity = '1';
                                }
                            } catch (e) {
                                console.error('[RESTORE_UI] restore exception:', e);
                                showToast('Restore error: ' + e.message, 'error');
                                textSpan.innerText = 'Restore Selected';
                                loader.classList.add('hidden');
                                btn.disabled = false;
                                btn.style.opacity = '1';
                            }
                        }, 100);
                    } else {
                        console.log('[RESTORE_UI] no AndroidBridge.restoreDatabase available');
                        showToast('Restore is only available on Android', 'error');
                    }
                });
            }

            function renderBackups() {
                const container = document.getElementById('backup-list-container');
                if (!container) return;
                container.innerHTML = '';
                const displayList = backupsData.slice(0, showingCount);
                console.log('[RESTORE_UI] showing latest backups count=' + displayList.length);

                if (displayList.length === 0) {
                    container.innerHTML = '<div style="color: var(--text-secondary); font-size: 0.875rem; padding: 16px 0; text-align: center;">No backups available</div>';
                    document.getElementById('show-more-backups-btn').style.display = 'none';
                    return;
                }

                displayList.forEach((backup, index) => {
                    const date = new Date(backup.timestamp);
                    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
                    const formattedDate = date.getDate().toString().padStart(2, '0') + ' ' +
                        months[date.getMonth()] + ' ' +
                        date.getFullYear() + ' ' +
                        date.getHours().toString().padStart(2, '0') + ':' +
                        date.getMinutes().toString().padStart(2, '0');
                    const sizeStr = (backup.sizeBytes / 1024).toFixed(1) + ' KB';

                    let typeLabel = 'Unknown Backup';
                    let typeColor = 'var(--text-secondary)';
                    let typeBg = 'var(--surface-alt, rgba(0,0,0,0.04))';
                    if (backup.backupType === 'manual') {
                        typeLabel = 'Manual Backup';
                        typeColor = '#4A90E2';
                        typeBg = 'rgba(74, 144, 226, 0.1)';
                    } else if (backup.backupType === 'auto') {
                        typeLabel = 'Auto Backup';
                        typeColor = '#50C878';
                        typeBg = 'rgba(80, 200, 120, 0.1)';
                    } else if (backup.backupType === 'emergency') {
                        typeLabel = 'Emergency Backup';
                        typeColor = '#E8913A';
                        typeBg = 'rgba(232, 145, 58, 0.1)';
                    }

                    const isSelected = selectedBackupFileName === backup.fileName;
                    const card = document.createElement('div');
                    card.style.cssText = `
                        min-height: 110px;
                        padding: 16px;
                        border-radius: 10px;
                        border: ${isSelected ? '2px solid #4A90E2' : '1px solid var(--border)'};
                        background: ${isSelected ? 'rgba(74, 144, 226, 0.06)' : 'var(--surface)'};
                        cursor: pointer;
                        transition: all 0.2s ease;
                        flex-shrink: 0;
                        box-sizing: border-box;
                        display: flex;
                        flex-direction: column;
                        justify-content: space-between;
                    `;

                    card.innerHTML = `
                        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px;">
                            <div style="display: inline-flex; align-items: center; gap: 6px;">
                                <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: ${typeColor}; flex-shrink: 0;"></span>
                                <span style="font-weight: 600; font-size: 0.875rem; color: var(--text-primary);">${typeLabel}</span>
                            </div>
                            ${isSelected ? '<span style="font-size: 0.7rem; font-weight: 600; color: #4A90E2; background: rgba(74, 144, 226, 0.1); padding: 2px 8px; border-radius: 4px;">SELECTED</span>' : ''}
                        </div>
                        <div style="font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 6px;">${formattedDate}</div>
                        <div style="display: flex; justify-content: space-between; align-items: flex-end;">
                            <div style="font-size: 0.75rem; color: var(--text-secondary); opacity: 0.8; word-break: break-all; padding-right: 8px; line-height: 1.4;">${backup.fileName}</div>
                            <div style="font-size: 0.75rem; color: var(--text-secondary); font-weight: 600; white-space: nowrap;">${sizeStr}</div>
                        </div>
                    `;

                    card.addEventListener('click', () => {
                        console.log('[RESTORE_UI] selected backup=' + backup.fileName);
                        selectedBackupFileName = backup.fileName;
                        renderBackups();
                        const restoreBtn = document.getElementById('mobile-do-restore-btn');
                        if (restoreBtn) {
                            restoreBtn.disabled = false;
                            restoreBtn.style.opacity = '1';
                        }
                    });

                    container.appendChild(card);
                });

                const showMoreBtn = document.getElementById('show-more-backups-btn');
                if (showMoreBtn) {
                    showMoreBtn.style.display = backupsData.length > showingCount ? 'block' : 'none';
                }
            }

            // Toggle panel open/closed
            if (ui.style.display === 'none') {
                selectedBackupFileName = null;
                showingCount = 5;
                if (window.AndroidBridge && typeof window.AndroidBridge.getAvailableBackups === 'function') {
                    try {
                        backupsData = JSON.parse(window.AndroidBridge.getAvailableBackups());
                        console.log('[RESTORE_UI] loaded backups count=' + backupsData.length);
                    } catch (e) {
                        backupsData = [];
                        console.error('[RESTORE_UI] error loading backups:', e);
                    }
                }
                const restoreBtn = document.getElementById('mobile-do-restore-btn');
                if (restoreBtn) {
                    restoreBtn.disabled = true;
                    restoreBtn.style.opacity = '0.5';
                }
                renderBackups();
                ui.style.display = 'block';
                // Scroll the restore panel into view
                setTimeout(() => ui.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 100);
            } else {
                ui.style.display = 'none';
            }
        });
    }

    // 4. Cleanup Old Records — Lifecycle Manager cleanup
    const drawerCleanupBtn = document.getElementById('drawer-cleanup-btn');
    if (drawerCleanupBtn) {
        drawerCleanupBtn.addEventListener('click', () => {
            if (window.AndroidBridge && typeof window.AndroidBridge.runCleanup === 'function') {
                try {
                    const resultJson = window.AndroidBridge.runCleanup();
                    const report = JSON.parse(resultJson);
                    const msg = `Cleanup Complete\nRecovered ${report.storageRecovered || '0 B'}\nRemoved ${report.filesRemoved || 0} backups`;
                    showToast(msg, 'success');
                    // Change 5: immediately refresh Database Center metrics
                    fetchDbStats();
                    fetchLifecycleStats();
                    refreshAvailableBackups();
                } catch (e) {
                    console.error('[LIFECYCLE] cleanup error:', e);
                    showToast('Cleanup failed: ' + e.message, 'error');
                }
            } else {
                showToast('Cleanup is only available on Android app', 'info');
            }
        });
    }

    // Wire up DB Center panel navigation
    setupDbCenterPanel();

    // Wire up Analytics panel navigation
    setupAnalyticsPanel();
}

function openNavDrawer() {
    if (!navDrawer || !navDrawerScrim) return;
    navDrawer.classList.add('is-open');
    navDrawer.setAttribute('aria-hidden', 'false');
    navDrawerScrim.classList.add('is-visible');
    navDrawerScrim.setAttribute('aria-hidden', 'false');
    if (hamburgerBtn) hamburgerBtn.setAttribute('aria-expanded', 'true');

    // Refresh system status every time drawer opens
    updateDrawerStatus();
}

function closeNavDrawer() {
    if (!navDrawer || !navDrawerScrim) return;
    navDrawer.classList.remove('is-open');
    navDrawer.setAttribute('aria-hidden', 'true');
    navDrawerScrim.classList.remove('is-visible');
    navDrawerScrim.setAttribute('aria-hidden', 'true');
    if (hamburgerBtn) hamburgerBtn.setAttribute('aria-expanded', 'false');
}

/**
 * Updates the SYSTEM section in the drawer (sync status only).
 * Full metrics are now in the DB Center panel via fetchDbStats().
 */
async function updateDrawerStatus() {
    const els = getDrawerStatusEls();

    // App Version — static
    if (els.appVersion) {
        els.appVersion.innerText = 'v1.0';
    }

    try {
        if (window.AndroidBridge && typeof window.AndroidBridge.getSyncState === 'function') {
            const statusJson = window.AndroidBridge.getSyncState();
            const status = JSON.parse(statusJson);

            if (els.syncStatus) {
                els.syncStatus.className = 'drawer-status-value drawer-sync-indicator';
                if (status.status === 'Synced') {
                    els.syncStatus.innerText = '● Synced';
                    els.syncStatus.classList.add('sync-ok');
                } else if (status.status === 'Syncing...') {
                    els.syncStatus.innerText = '● Syncing...';
                    els.syncStatus.style.color = '#2196f3'; // Blue
                } else if (status.status === 'Pending Sync') {
                    els.syncStatus.innerText = `● Pending (${status.pendingQueue})`;
                    els.syncStatus.classList.add('sync-pending'); // Yellow/Orange
                } else if (status.status === 'Offline') {
                    els.syncStatus.innerText = '● Offline';
                    els.syncStatus.classList.add('sync-pending');
                } else if (status.status === 'Sign In Required') {
                    els.syncStatus.innerText = '● Sign In Required';
                    els.syncStatus.style.color = '#9e9e9e'; // Gray
                } else if (status.hasError) {
                    els.syncStatus.innerText = '● Error';
                    els.syncStatus.classList.add('sync-error'); // Red
                } else {
                    els.syncStatus.innerText = `● ${status.status}`;
                    els.syncStatus.style.color = '#9e9e9e';
                }
            }
        }
    } catch (e) {
        if (els.syncStatus) {
            els.syncStatus.className = 'drawer-status-value drawer-sync-indicator sync-error';
            els.syncStatus.innerText = '● Error';
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DESKTOP MODAL — overlay, unchanged logic (≥769px only)
// ═══════════════════════════════════════════════════════════════════

function openModal(mode, data = null) {
    if (!modalWrapper || !modalBackdrop) return;

    // Return form to desktop modal card section before showing modal
    const desktopSection = modalWrapper.querySelector('section.form-section');
    ensureFormIn(desktopSection);

    if (modalTitle) modalTitle.innerText = mode === 'edit' ? 'Edit Entry' : 'Record New Entry';
    if (saveBtnText) saveBtnText.innerText = mode === 'edit' ? 'Update Ledger Entry' : 'Save to Ledger';

    populateForm(mode, data);

    modalBackdrop.classList.remove('hidden');
    modalWrapper.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
}

function closeModal() {
    if (!modalWrapper || !modalBackdrop) return;
    modalWrapper.classList.add('hidden');
    modalBackdrop.classList.add('hidden');
    document.body.style.overflow = '';
    resetForm();
}

const currencyFormatter = new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2
});

function formatMobileDate(dateStr) {
    try {
        if (!dateStr) return '-';
        return dateStr;
    } catch (e) { return dateStr; }
}

// --- UI Feedback ---

function showToast(message, type = 'success') {
    console.log('[TOAST] showToast called');
    console.log('[TOAST] raw message:', message);
    console.log('[TOAST] message type:', typeof message);
    console.log('[TOAST] message is null:', message === null);
    console.log('[TOAST] message is undefined:', message === undefined);
    console.log('[TOAST] message is empty string:', message === '');
    console.log('[TOAST] toast type:', type);
    console.log('[TOAST] toastContainer exists:', !!toastContainer);
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `<span class="toast-msg">${message}</span>`;
    console.log('[TOAST] toast innerHTML:', toast.innerHTML);
    toastContainer.appendChild(toast);
    console.log('[TOAST] toast appended to container');
    // Check computed styles
    const toastStyles = window.getComputedStyle(toast);
    const msgSpan = toast.querySelector('.toast-msg');
    const msgStyles = msgSpan ? window.getComputedStyle(msgSpan) : null;
    console.log('[TOAST] toast background-color:', toastStyles.backgroundColor);
    console.log('[TOAST] toast color:', toastStyles.color);
    console.log('[TOAST] .toast-msg color:', msgStyles ? msgStyles.color : 'NO SPAN');
    console.log('[TOAST] .toast-msg innerText:', msgSpan ? msgSpan.innerText : 'NO SPAN');
    console.log('[TOAST] toast offsetWidth:', toast.offsetWidth);
    console.log('[TOAST] toast offsetHeight:', toast.offsetHeight);
    setTimeout(() => {
        toast.classList.add('toast-fade-out');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

function setLoading(btn, isLoading, originalText) {
    if (!btn) return;
    const loader = btn.querySelector('.btn-loader');
    const textSpan = btn.querySelector('.btn-text');
    btn.disabled = isLoading;
    if (isLoading) {
        if (loader) loader.classList.remove('hidden');
        if (textSpan) textSpan.innerText = 'Working...';
    } else {
        if (loader) loader.classList.add('hidden');
        if (textSpan) textSpan.innerText = originalText;
    }
}

// --- Data Operations ---

async function loadTransactions(append = false) {
    if (isLoadingMore) return;
    isLoadingMore = true;

    if (loadMoreBtn) {
        loadMoreBtn.innerText = 'Loading...';
        loadMoreBtn.disabled = true;
    }

    try {
        let data;
        if (window.AndroidBridge) {
            const bridgeData = window.AndroidBridge.getTransactions(currentPage, 30);
            data = JSON.parse(bridgeData);
        } else {
            const response = await fetch(`${API_BASE_URL}?page=${currentPage}&limit=30`);
            if (!response.ok) throw new Error('Fetch failed');
            data = await response.json();
        }

        if (append) {
            transactions = [...transactions, ...data.transactions];
        } else {
            transactions = data.transactions;
        }

        hasMore = data.hasMore;

        if (loadMoreContainer) {
            loadMoreContainer.classList.toggle('hidden', !hasMore);
        }

        if (!append && currentPage === 1) {
            const currentYear = new Date().getFullYear().toString();
            const hasCurrentYearRecords = transactions.some(t => t.date.startsWith(currentYear));
            if (hasCurrentYearRecords && filterYear && !filterYear.value) {
                filterYear.value = currentYear;
            }
        }

        applyFilters();
        updateDashboard(null, null, null, data);
    } catch (error) {
        showToast('Connection failed: Server unreachable', 'error');
        console.error(error);
    } finally {
        isLoadingMore = false;
        if (loadMoreBtn) {
            loadMoreBtn.innerText = 'Load More Transactions';
            loadMoreBtn.disabled = false;
        }
    }
}

async function updateBackupStatus() {
    try {
        let status = null;
        if (window.AndroidBridge && typeof window.AndroidBridge.getBackupStatus === 'function') {
            const statusJson = window.AndroidBridge.getBackupStatus();
            status = JSON.parse(statusJson);
        } else {
            const response = await fetch(`${BACKUP_BASE_URL}/status`);
            if (response.ok) {
                status = await response.json();
            }
        }

        if (status) {
            if (backupStatusEl) {
                backupStatusEl.className = '';
                if (status.status === 'Pending Sync') {
                    backupStatusEl.innerText = `Pending: ${status.transactionsSinceLast}/10`;
                    backupStatusEl.classList.add('status-pending');
                } else if (status.status === 'Synced') {
                    backupStatusEl.innerText = 'Synced';
                    backupStatusEl.classList.add('status-synced');
                } else {
                    backupStatusEl.innerText = status.status;
                }
            }

            if (lastBackupTimeEl) lastBackupTimeEl.innerText = status.lastBackupTime;
            if (autoBackupCountEl) autoBackupCountEl.innerText = status.autoCount;
            if (manualBackupCountEl) manualBackupCountEl.innerText = status.manualCount;
        }
    } catch (e) {
        console.error("Status check failed", e);
        if (backupStatusEl) {
            backupStatusEl.innerText = 'Sync Error';
            backupStatusEl.className = 'status-error';
        }
    }
}


function applyFilters() {
    const searchTerm = searchInput.value.toLowerCase();
    const month = filterMonth.value;
    const year = filterYear.value;
    const category = filterCategory.value;
    const type = filterType.value;

    const filtered = transactions.filter(t => {
        const matchesSearch =
            (t.category && t.category.toLowerCase().includes(searchTerm)) ||
            (t.paidTo && t.paidTo.toLowerCase().includes(searchTerm)) ||
            (t.notes && t.notes.toLowerCase().includes(searchTerm));

        const dateParts = (t.date || "").split('-');
        const tYear = dateParts[0] || "";
        const tMonth = dateParts[1] || "";

        return matchesSearch &&
               (month === "" || tMonth === month) &&
               (year === "" || tYear === year) &&
               (category === "" || t.category === category) &&
               (type === "" || t.entryType === type);
    });

    renderTable(filtered);
    updateDashboard(filtered, month, year);
}

function renderTable(data) {
    historyBody.innerHTML = '';
    recordCountEl.innerText = `${data.length} Records`;
    if (data.length === 0) {
        emptyStateEl.classList.remove('hidden');
        return;
    }

    emptyStateEl.classList.add('hidden');
    const isMobile = window.innerWidth <= 768;
    window.lastWasMobile = isMobile;

    data.forEach(t => {
        const isCredit = t.entryType === 'Credit';
        const row = document.createElement('tr');

        // ✅ AFTER — divs wrapped in a valid <td colspan="9">
        if (isMobile) {
            row.innerHTML = `
                <td colspan="9" style="padding:0;border:none;">
                    <div class="row-compact" onclick="toggleRow(this)">
                        <div class="mobile-date-col">${t.date}</div>
                        <div class="mobile-indicator">
                            <svg class="chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
                                <polyline points="9 18 15 12 9 6"></polyline>
                            </svg>
                        </div>
                        <div class="mobile-category-col">${escapeHtml(t.category)}</div>
                        <div class="mobile-amount-col ${isCredit ? 'credit-text' : 'debit-text'}">
                            ${isCredit ? '+' : '-'}₹${parseFloat(t.amount).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                        </div>
                    </div>
                    <div class="row-details">
                        <div class="details-grid">
                            <div class="detail-item">
                                <span class="detail-label">PAID TO / FROM</span>
                                <span class="detail-value">${escapeHtml(t.paidTo || '-')}</span>
                            </div>
                            <div class="detail-item">
                                <span class="detail-label">EXPENSE TYPE</span>
                                <span class="detail-value">${escapeHtml(t.expenseType || '-')}</span>
                            </div>
                            <div class="detail-item">
                                <span class="detail-label">BALANCE AFTER</span>
                                <span class="detail-value">₹${parseFloat(t.balanceAfter).toLocaleString('en-IN', { minimumFractionDigits: 2 })}</span>
                            </div>
                            <div class="detail-item">
                                <span class="detail-label">ENTRY TYPE</span>
                                <span class="detail-value">${t.entryType}</span>
                            </div>
                            <div class="detail-item detail-full-width">
                                <span class="detail-label">NOTES / REFERENCE</span>
                                <span class="detail-value">${escapeHtml(t.notes || 'No reference notes')}</span>
                            </div>
                        </div>
                        <div class="details-actions">
                            <button class="btn-action-mobile" onclick="event.stopPropagation(); editTransaction('${t.id}')">Edit Entry</button>
                            <button class="btn-action-mobile" onclick="event.stopPropagation(); deleteTransaction('${t.id}')">Delete</button>
                        </div>
                    </div>
                </td>
            `;
        } else {
            row.innerHTML = `
                <td data-label="Date">
                    <span class="desktop-date">${t.date}</span>
                </td>
                <td data-label="Transaction">
                    <div class="transaction-info">
                        <div class="category-cell">${escapeHtml(t.category)}</div>
                        <div class="paid-to-text">${escapeHtml(t.paidTo || '-')}</div>
                    </div>
                </td>
                <td data-label="Method">${escapeHtml(t.expenseType)}</td>
                <td data-label="Notes" class="notes-cell" title="${escapeHtml(t.notes)}">${escapeHtml(t.notes || '—')}</td>
                <td data-label="Type"><span class="status-pill ${isCredit ? 'status-added' : 'status-expense'}">${isCredit ? 'ADDED' : 'EXPENSE'}</span></td>
                <td data-label="Expense" class="text-right" style="font-weight: 500;">${!isCredit ? parseFloat(t.amount).toFixed(2) : '—'}</td>
                <td data-label="Added" class="text-right" style="font-weight: 500;">${isCredit ? parseFloat(t.amount).toFixed(2) : '—'}</td>
                <td data-label="Balance" class="text-right" style="font-weight: 600;">${parseFloat(t.balanceAfter).toFixed(2)}</td>
                <td data-label="Actions" class="text-center">
                    <div class="action-buttons">
                        <button class="btn-action btn-edit" onclick="editTransaction('${t.id}')">Edit</button>
                        <button class="btn-action btn-delete" onclick="deleteTransaction('${t.id}')">Del</button>
                    </div>
                </td>
            `;
        }
        historyBody.appendChild(row);
    });
}

function updateDashboard(filtered, selMonth, selYear, pagedData = null) {
    if (pagedData) {
        const globalBalance = (pagedData.globalBalance === undefined || isNaN(pagedData.globalBalance)) ? 0.00 : pagedData.globalBalance;
        const totalCredit = (pagedData.totalCredit === undefined || isNaN(pagedData.totalCredit)) ? 0.00 : pagedData.totalCredit;
        const totalDebit = (pagedData.totalDebit === undefined || isNaN(pagedData.totalDebit)) ? 0.00 : pagedData.totalDebit;
        balanceEl.innerText = currencyFormatter.format(globalBalance || 0);
        creditEl.innerText = currencyFormatter.format(totalCredit || 0);
        debitEl.innerText = currencyFormatter.format(totalDebit || 0);
    }

    if (filtered) {
        const periodBal = filtered.reduce((s, t) => s + (t.entryType === 'Credit' ? parseFloat(t.amount) : -parseFloat(t.amount)), 0);
        monthBalanceEl.innerText = (periodBal >= 0 ? '+' : '') + currencyFormatter.format(periodBal);
        monthBalanceEl.style.color = periodBal >= 0 ? 'var(--primary)' : 'var(--danger)';
    }

    const monthName = selMonth ? filterMonth.options[filterMonth.selectedIndex].text : '';
    currentViewLabel.innerText = (selMonth || selYear) ? `Viewing: ${monthName} ${selYear}`.trim() : 'Viewing: Recent Transactions';
}

function toggleRow(element) {
    const row = element.parentElement;
    const isExpanded = row.classList.contains('tr-expanded');

    document.querySelectorAll('.ledger-table tr').forEach(r => {
        if (r !== row) r.classList.remove('tr-expanded');
    });

    if (isExpanded) {
        row.classList.remove('tr-expanded');
    } else {
        row.classList.add('tr-expanded');
        setTimeout(() => {
            const rect = row.getBoundingClientRect();
            if (rect.bottom > window.innerHeight) {
                row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        }, 300);
    }
}

// --- CRUD ---

if (form) {
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!validateForm()) return;
        const originalText = editingId ? 'Update Ledger Entry' : 'Save to Ledger';
        setLoading(saveBtn, true, originalText);
        const data = {
            date: document.getElementById('date').value,
            entryType: document.getElementById('type').value,
            amount: document.getElementById('amount').value,
            category: document.getElementById('category').value,
            expenseType: document.getElementById('source').value,
            paidTo: document.getElementById('person').value,
            notes: document.getElementById('notes').value
        };
        try {
            if (window.AndroidBridge) {
                let bridgeRes;
                if (editingId) {
                    bridgeRes = window.AndroidBridge.updateTransaction(editingId.toString(), JSON.stringify(data));
                } else {
                    bridgeRes = window.AndroidBridge.addTransaction(JSON.stringify(data));
                }

                const responseData = JSON.parse(bridgeRes);
                if (responseData.status === 'ok') {
                    showToast(editingId ? 'Entry updated' : 'Entry added');
                    if (isMobile()) { closeMobileForm(); } else { closeModal(); }
                    currentPage = 1;
                    loadTransactions();
                    updateBackupStatus();
                } else {
                    showToast('Save failed: ' + responseData.error, 'error');
                }
            } else {
                const response = await fetch(editingId ? `${API_BASE_URL}/${editingId}` : API_BASE_URL, {
                    method: editingId ? 'PUT' : 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(data)
                });
                if (response.ok) {
                    showToast(editingId ? 'Entry updated' : 'Entry added');
                    // Close whichever surface is active
                    if (isMobile()) { closeMobileForm(); } else { closeModal(); }
                    currentPage = 1;
                    loadTransactions();
                    updateBackupStatus();
                } else {
                    const err = await response.text();
                    showToast('Save failed: ' + (err || 'Server error'), 'error');
                }
            }
        } catch (e) {
            showToast('Error saving data', 'error');
        }
        finally { setLoading(saveBtn, false, editingId ? 'Update Ledger Entry' : 'Save to Ledger'); }
    });
}

async function deleteTransaction(id) {
    if (!confirm('Delete this entry?')) return;
    try {
        if (window.AndroidBridge) {
            const bridgeRes = window.AndroidBridge.deleteTransaction(id.toString());
            const responseData = JSON.parse(bridgeRes);
            if (responseData.status === 'ok') {
                showToast('Deleted');
                currentPage = 1;
                loadTransactions();
                updateBackupStatus();
            } else {
                showToast('Delete failed: ' + responseData.error, 'error');
            }
        } else {
            const response = await fetch(`${API_BASE_URL}/${id}`, { method: 'DELETE' });
            if (response.ok) {
                showToast('Deleted');
                currentPage = 1;
                loadTransactions();
                updateBackupStatus();
            } else {
                const err = await response.text();
                showToast('Delete failed: ' + (err || 'Server error'), 'error');
            }
        }
    } catch (e) {
        showToast('Delete failed', 'error');
    }
}

function editTransaction(id) {
    const t = transactions.find(x => String(x.id) === String(id));
    if (!t) return;
    // Mobile: expand inline form with prefilled data
    // Desktop: open overlay modal as before
    if (isMobile()) {
        openMobileForm('edit', t);
    } else {
        openModal('edit', t);
    }
}

function triggerExport(format, btn) {
    const originalText = format === 'excel' ? 'Excel' : 'CSV';
    setLoading(btn, true, originalText);
    setTimeout(() => {
        window.location.href = `${EXPORT_BASE_URL}/${format}?${new URLSearchParams({ month: filterMonth.value, year: filterYear.value, category: filterCategory.value, search: searchInput.value }).toString()}`;
        setLoading(btn, false, originalText);
        // Track export timestamp for drawer System Status
        const now = new Date();
        lastExportTime = now.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' }) + ' ' + now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });
    }, 800);
}

function validateField(input) {
    const isValid = input.required ? !!input.value : true;
    input.parentElement.classList.toggle('invalid', !isValid);
    return isValid;
}

function validateForm() {
    let valid = true;
    if (form) {
        form.querySelectorAll('input[required], select[required]').forEach(i => { if(!validateField(i)) valid = false; });
    }
    return valid;
}

function resetForm() {
    if (form) form.reset();
    editingId = null;
    if (saveBtnText) saveBtnText.innerText = 'Save to Ledger';
    const dateInput = document.getElementById('date');
    if (dateInput) dateInput.valueAsDate = new Date();
}

function escapeHtml(t) {
    if (!t) return '';
    const d = document.createElement('div');
    d.textContent = t;
    return d.innerHTML;
}

// ═══════════════════════════════════════════════════════════════════
// ANALYTICS PANEL — dedicated insight dashboard
// ═══════════════════════════════════════════════════════════════════

function setupAnalyticsPanel() {
    const link     = document.getElementById('drawer-analytics-link');
    const panel    = document.getElementById('analytics-panel');
    const scrim    = document.getElementById('analytics-scrim');
    const closeBtn = document.getElementById('analytics-close');
    if (!link || !panel) return;

    link.addEventListener('click', () => {
        closeNavDrawer();
        setTimeout(() => openAnalyticsPanel(), 320);
    });

    if (closeBtn) closeBtn.addEventListener('click', () => closeAnalyticsPanel());
    if (scrim) scrim.addEventListener('click', () => closeAnalyticsPanel());
}

function openAnalyticsPanel() {
    const panel = document.getElementById('analytics-panel');
    const scrim = document.getElementById('analytics-scrim');
    if (!panel) return;

    panel.classList.add('is-open');
    panel.setAttribute('aria-hidden', 'false');
    if (scrim) {
        scrim.classList.add('is-visible');
        scrim.setAttribute('aria-hidden', 'false');
    }

    loadAnalyticsData();
}

function closeAnalyticsPanel() {
    const panel = document.getElementById('analytics-panel');
    const scrim = document.getElementById('analytics-scrim');
    if (!panel) return;

    panel.classList.remove('is-open');
    panel.setAttribute('aria-hidden', 'true');
    if (scrim) {
        scrim.classList.remove('is-visible');
        scrim.setAttribute('aria-hidden', 'true');
    }
}

let analyticsCharts = {};

function parseLedgerDate(dateString) {
    if (dateString.includes('-')) {
        const [year, month, day] = dateString.split('-');
        return new Date(year, month - 1, day);
    }
    const [day, month, year] = dateString.split('/');
    return new Date(year, month - 1, day);
}

async function loadAnalyticsData() {
    const loader = document.getElementById('analytics-loader');
    if (loader) loader.classList.remove('hidden');

    try {
        const raw = window.AndroidBridge.getAllTransactionsForAnalytics();
        const data = JSON.parse(raw);

        processAnalyticsData(data);
    } catch (e) {
        console.error('Analytics load error:', e);
        showToast('Failed to load analytics', 'error');
    } finally {
        if (loader) loader.classList.add('hidden');
    }
}

function processAnalyticsData(data) {
    if (!data || data.length === 0) return;

    const now = new Date(); // Keep for "current period" logic
    const currentYear = now.getFullYear().toString();
    const currentMonth = String(now.getMonth() + 1).padStart(2, '0');

    // Insight Calculations
    let currentMonthExpenses = 0;
    let currentMonthIncome = 0;
    let totalExpenses = 0;
    let categoryTotals = {};
    let dailyExpenses = {};

    data.forEach(t => {
        const amount = parseFloat(t.amount);
        const ledgerDate = parseLedgerDate(t.date);
        const y = ledgerDate.getFullYear().toString();
        const m = String(ledgerDate.getMonth() + 1).padStart(2, '0');
        const d = String(ledgerDate.getDate()).padStart(2, '0');

        if (t.entryType === 'Debit') {
            totalExpenses += amount;
            categoryTotals[t.category] = (categoryTotals[t.category] || 0) + amount;

            if (y === currentYear && m === currentMonth) {
                currentMonthExpenses += amount;
                dailyExpenses[d] = (dailyExpenses[d] || 0) + amount;
            }
        } else if (t.entryType === 'Credit') {
            if (y === currentYear && m === currentMonth) {
                currentMonthIncome += amount;
            }
        }
    });

    // 1. Highest Spend Category
    let highestCat = '—';
    let highestAmount = 0;
    for (const [cat, amt] of Object.entries(categoryTotals)) {
        if (amt > highestAmount) {
            highestAmount = amt;
            highestCat = cat;
        }
    }

    document.getElementById('insight-highest-spend-cat').innerText = highestCat;
    document.getElementById('insight-highest-spend-val').innerText = `₹${highestAmount.toFixed(2)}`;

    // 2. Avg Daily Spend (Current Month)
    const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
    const currentDay = now.getDate();
    const avgDaily = currentMonthExpenses / currentDay; // avg up to today

    document.getElementById('insight-avg-daily').innerText = `₹${avgDaily.toFixed(0)}`;

    // 3. Savings Rate (Current Month)
    let savingsRate = 0;
    if (currentMonthIncome > 0) {
        const savings = currentMonthIncome - currentMonthExpenses;
        savingsRate = savings > 0 ? (savings / currentMonthIncome) * 100 : 0;
    }
    document.getElementById('insight-savings').innerText = `${savingsRate.toFixed(1)}%`;

    // Render Charts
    renderCharts(data);
}

function getCssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

function renderCharts(data) {
    if (typeof Chart === 'undefined') return;

    // Destroy existing chart instances to avoid canvas reuse errors
    if (analyticsCharts.monthlyTrend) analyticsCharts.monthlyTrend.destroy();
    if (analyticsCharts.categoryBreakdown) analyticsCharts.categoryBreakdown.destroy();
    if (analyticsCharts.incomeVsExpense) analyticsCharts.incomeVsExpense.destroy();

    const currentYear = new Date().getFullYear().toString();

    // Aggregation Logic
    let monthlyExpenses = { '01':0, '02':0, '03':0, '04':0, '05':0, '06':0, '07':0, '08':0, '09':0, '10':0, '11':0, '12':0 };
    let monthlyIncome = { '01':0, '02':0, '03':0, '04':0, '05':0, '06':0, '07':0, '08':0, '09':0, '10':0, '11':0, '12':0 };
    let categoryTotals = {};

    data.forEach(t => {
        const ledgerDate = parseLedgerDate(t.date);
        const y = ledgerDate.getFullYear().toString();
        const m = String(ledgerDate.getMonth() + 1).padStart(2, '0');

        if (y !== currentYear) return; // Only process current year for charts

        const amount = parseFloat(t.amount);

        if (t.entryType === 'Debit') {
            monthlyExpenses[m] += amount;
            categoryTotals[t.category] = (categoryTotals[t.category] || 0) + amount;
        } else if (t.entryType === 'Credit') {
            monthlyIncome[m] += amount;
        }
    });

    // Only show months that actually exist in the database or up to current month to avoid hallucinated future data
    // Let's filter to only include months with data
    const monthsWithData = new Set();
    Object.keys(monthlyExpenses).forEach(m => {
        if (monthlyExpenses[m] > 0 || monthlyIncome[m] > 0) {
            monthsWithData.add(m);
        }
    });

    // If no data, use current month as fallback so chart isn't totally empty
    if (monthsWithData.size === 0) {
        const now = new Date();
        monthsWithData.add(String(now.getMonth() + 1).padStart(2, '0'));
    }

    const allMonths = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11', '12'];
    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

    // Find min and max month with data
    let minMonthIdx = 11;
    let maxMonthIdx = 0;
    monthsWithData.forEach(m => {
        const idx = parseInt(m, 10) - 1;
        if (idx < minMonthIdx) minMonthIdx = idx;
        if (idx > maxMonthIdx) maxMonthIdx = idx;
    });

    const activeMonthLabels = monthNames.slice(minMonthIdx, maxMonthIdx + 1);
    const activeMonthKeys = allMonths.slice(minMonthIdx, maxMonthIdx + 1);
    const expenseData = activeMonthKeys.map(k => monthlyExpenses[k]);
    const incomeData = activeMonthKeys.map(k => monthlyIncome[k]);

    // Sort categories for breakdown
    const sortedCategories = Object.entries(categoryTotals).sort((a,b) => b[1] - a[1]).slice(0, 6); // top 6
    const catLabels = sortedCategories.map(c => c[0]);
    const catData = sortedCategories.map(c => c[1]);

    // Common Chart Options (Mobile First)
    const commonOptions = {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                position: 'bottom',
                labels: { font: { family: 'Inter, sans-serif', size: 10 }, color: getCssVar('--text-secondary') || '#64748b', boxWidth: 12 }
            },
            tooltip: {
                backgroundColor: getCssVar('--surface') || 'rgba(15, 23, 42, 0.9)',
                titleFont: { family: 'Inter, sans-serif', size: 12 },
                bodyFont: { family: 'Inter, sans-serif', size: 13 },
                padding: 10,
                cornerRadius: 8,
                displayColors: false
            }
        },
        layout: {
            padding: {
                bottom: 20
            }
        },
        scales: {
            x: { grid: { display: false }, ticks: { font: { size: 10 }, color: getCssVar('--text-secondary') || '#94a3b8' } },
            y: { border: { display: false }, grid: { color: getCssVar('--border') || '#f1f5f9' }, ticks: { font: { size: 10 }, color: getCssVar('--text-secondary') || '#94a3b8', maxTicksLimit: 5 } }
        }
    };

    // 1. Monthly Expense Trend (Line)
    const ctxTrend = document.getElementById('monthlyTrendChart').getContext('2d');
    const gradientTrend = ctxTrend.createLinearGradient(0, 0, 0, 250);
    gradientTrend.addColorStop(0, getCssVar('--danger-soft') || 'rgba(239, 68, 68, 0.2)');
    gradientTrend.addColorStop(1, 'rgba(239, 68, 68, 0)');

    analyticsCharts.monthlyTrend = new Chart(ctxTrend, {
        type: 'line',
        data: {
            labels: activeMonthLabels,
            datasets: [{
                label: 'Expenses',
                data: expenseData,
                borderColor: getCssVar('--danger') || '#ef4444',
                backgroundColor: gradientTrend,
                borderWidth: 2,
                pointBackgroundColor: getCssVar('--surface') || '#ffffff',
                pointBorderColor: getCssVar('--danger') || '#ef4444',
                pointBorderWidth: 2,
                pointRadius: 3,
                fill: true,
                tension: 0.4
            }]
        },
        options: {
            ...commonOptions,
            plugins: { ...commonOptions.plugins, legend: { display: false } }
        }
    });

    // 2. Category Breakdown (Doughnut)
    const ctxCategory = document.getElementById('categoryBreakdownChart').getContext('2d');
    const bgColors = [getCssVar('--accent'), getCssVar('--success'), getCssVar('--warning'), getCssVar('--danger'), '#8b5cf6', getCssVar('--text-secondary')];

    analyticsCharts.categoryBreakdown = new Chart(ctxCategory, {
        type: 'doughnut',
        data: {
            labels: catLabels,
            datasets: [{
                data: catData,
                backgroundColor: bgColors,
                borderWidth: 0,
                hoverOffset: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            cutout: '70%',
            layout: {
                padding: {
                    bottom: 20
                }
            },
            plugins: {
                legend: { position: 'right', labels: { font: { family: 'Inter, sans-serif', size: 10 }, color: getCssVar('--text-secondary') || '#64748b', boxWidth: 10, padding: 15 } },
                tooltip: commonOptions.plugins.tooltip
            }
        }
    });

    // 3. Income vs Expense (Bar)
    const ctxIncExp = document.getElementById('incomeVsExpenseChart').getContext('2d');

    analyticsCharts.incomeVsExpense = new Chart(ctxIncExp, {
        type: 'bar',
        data: {
            labels: activeMonthLabels,
            datasets: [
                {
                    label: 'Income',
                    data: incomeData,
                    backgroundColor: getCssVar('--success') || '#10b981',
                    borderRadius: 4,
                    barPercentage: 0.6,
                    categoryPercentage: 0.8
                },
                {
                    label: 'Expense',
                    data: expenseData,
                    backgroundColor: getCssVar('--danger') || '#ef4444',
                    borderRadius: 4,
                    barPercentage: 0.6,
                    categoryPercentage: 0.8
                }
            ]
        },
        options: {
            ...commonOptions,
            interaction: { mode: 'index', intersect: false },
            layout: {
                padding: {
                    bottom: 20
                }
            }
        }
    });
}

// ═══════════════════════════════════════════════════════════════════
// DB CENTER PANEL — isolated mobile slide panel, no global state touch
// ═══════════════════════════════════════════════════════════════════

function setupDbCenterPanel() {
    const link     = document.getElementById('drawer-db-center-link');
    const panel    = document.getElementById('db-center-panel');
    const scrim    = document.getElementById('db-center-scrim');
    const closeBtn = document.getElementById('db-center-close');
    if (!link || !panel) return;

    // "Database Center →" link in drawer
    link.addEventListener('click', () => {
        closeNavDrawer();
        // Wait for drawer close animation before opening panel
        setTimeout(() => openDbCenter(), 320);
    });

    // Close button (back arrow)
    if (closeBtn) closeBtn.addEventListener('click', () => closeDbCenter());

    // Scrim tap closes panel
    if (scrim) scrim.addEventListener('click', () => closeDbCenter());
}

function openDbCenter() {
    const panel = document.getElementById('db-center-panel');
    const scrim = document.getElementById('db-center-scrim');
    if (!panel) return;

    panel.classList.add('is-open');
    panel.setAttribute('aria-hidden', 'false');
    if (scrim) {
        scrim.classList.add('is-visible');
        scrim.setAttribute('aria-hidden', 'false');
    }

    // Fetch live stats every time panel opens
    fetchDbStats();
    fetchLifecycleStats();
    if (typeof window.refreshBackupMetrics === 'function') {
        window.refreshBackupMetrics();
    }
    refreshAvailableBackups();
}

function refreshAvailableBackups() {
    console.log('[RESTORE] refreshAvailableBackups called');
    console.log('[RESTORE] AndroidBridge present:', !!window.AndroidBridge);
    console.log('[RESTORE] getAvailableBackups present:', !!(window.AndroidBridge && typeof window.AndroidBridge.getAvailableBackups === 'function'));
    console.log('[RESTORE] availableBackupsSelect element:', !!availableBackupsSelect);
    if (window.AndroidBridge && typeof window.AndroidBridge.getAvailableBackups === 'function') {
        try {
            console.log('[RESTORE] requesting backups from AndroidBridge');
            const backupsJson = window.AndroidBridge.getAvailableBackups();
            console.log('[RESTORE] raw backup payload:', backupsJson);
            const backups = JSON.parse(backupsJson);
            console.log('[RESTORE] parsed backup payload:', JSON.stringify(backups));
            console.log('[RESTORE] backup count:', backups.length);

            if (!availableBackupsSelect) {
                console.log('[RESTORE] DEAD END: availableBackupsSelect is null');
                return;
            }

            availableBackupsSelect.innerHTML = '';

            if (backups.length === 0) {
                console.log('[RESTORE] no backups found, showing placeholder');
                const opt = document.createElement('option');
                opt.value = "";
                opt.disabled = true;
                opt.selected = true;
                opt.textContent = "No backups available";
                availableBackupsSelect.appendChild(opt);
            } else {
                backups.forEach((backup, i) => {
                    console.log(`[RESTORE] backup[${i}]:`, backup.fileName, backup.backupType, backup.sizeBytes, 'bytes');
                    const opt = document.createElement('option');
                    opt.value = backup.fileName;
                    // Format timestamp
                    const date = new Date(backup.timestamp);
                    opt.textContent = `${backup.fileName} (${backup.backupType}, ${(backup.sizeBytes / 1024).toFixed(1)} KB) - ${date.toLocaleString()}`;
                    availableBackupsSelect.appendChild(opt);
                });
                console.log('[RESTORE] select populated with', backups.length, 'options');
            }
        } catch (e) {
            console.error('[RESTORE] error fetching available backups:', e);
        }
    } else {
        console.log('[RESTORE] no AndroidBridge, showing desktop placeholder');
        if (availableBackupsSelect) {
            availableBackupsSelect.innerHTML = '<option value="" disabled selected>Desktop Mode: Backups unavailable</option>';
        }
    }
}

function closeDbCenter() {
    const panel = document.getElementById('db-center-panel');
    const scrim = document.getElementById('db-center-scrim');
    if (!panel) return;

    panel.classList.remove('is-open');
    panel.setAttribute('aria-hidden', 'true');
    if (scrim) {
        scrim.classList.remove('is-visible');
        scrim.setAttribute('aria-hidden', 'true');
    }
}

/**
 * Fetches real database stats from GET /db/stats.
 * Populates the DB Center panel metrics with backend-sourced data.
 * Falls back gracefully if endpoint is unavailable.
 */
async function fetchDbStats() {
    const els = {
        totalTxns:  document.getElementById('dbcenter-total-txns'),
        dbSize:     document.getElementById('dbcenter-db-size'),
        lastBackup: document.getElementById('dbcenter-last-backup'),
        lastExport: document.getElementById('dbcenter-last-export'),
        cloudStatus: document.getElementById('dbcenter-cloud-status'),
        cloudConnection: document.getElementById('dbcenter-cloud-connection'),
        cloudPending: document.getElementById('dbcenter-cloud-pending'),
        cloudLastSync: document.getElementById('dbcenter-cloud-last-sync'),
        cloudRecords: document.getElementById('dbcenter-cloud-records'),
        cloudLocalRecords: document.getElementById('dbcenter-cloud-local-records')
    };

    try {
        let stats = null;

        if (window.AndroidBridge && typeof window.AndroidBridge.getDbStats === 'function') {
            const statsJson = window.AndroidBridge.getDbStats();
            stats = JSON.parse(statsJson);
        } else {
            const response = await fetch('/db/stats');
            if (response.ok) {
                stats = await response.json();
            }
        }

        if (stats) {
            if (els.totalTxns) {
                const count = parseInt(stats.totalTransactions, 10) || 0;
                els.totalTxns.innerText = count.toLocaleString();
            }

            if (els.dbSize) {
                const bytes = parseInt(stats.databaseSizeBytes, 10) || 0;
                if (bytes >= 1048576) {
                    els.dbSize.innerText = `${(bytes / 1048576).toFixed(1)} MB`;
                } else if (bytes >= 1024) {
                    els.dbSize.innerText = `${(bytes / 1024).toFixed(0)} KB`;
                } else {
                    els.dbSize.innerText = `${bytes} B`;
                }
            }

            if (els.lastBackup) {
                els.lastBackup.innerText = stats.lastBackupTime || 'Never';
            }
        }

        if (window.AndroidBridge && typeof window.AndroidBridge.getSyncState === 'function') {
            const syncStateStr = window.AndroidBridge.getSyncState();
            const syncStatus = JSON.parse(syncStateStr);

            if (els.cloudStatus) {
                els.cloudStatus.className = 'db-center-sync-badge';
                if (syncStatus.status === 'Synced') {
                    els.cloudStatus.innerText = '🟢 Synced';
                    els.cloudStatus.classList.add('sync-ok');
                } else if (syncStatus.status === 'Syncing...') {
                    els.cloudStatus.innerText = '🔵 Syncing...';
                    els.cloudStatus.style.color = '#2196f3';
                } else if (syncStatus.status === 'Pending Sync') {
                    els.cloudStatus.innerText = `🟡 Pending (${syncStatus.pendingQueue})`;
                    els.cloudStatus.classList.add('sync-pending');
                } else if (syncStatus.status === 'Offline') {
                    els.cloudStatus.innerText = '🟠 Offline';
                    els.cloudStatus.classList.add('sync-pending');
                } else if (syncStatus.status === 'Sign In Required') {
                    els.cloudStatus.innerText = '⚪ Sign In Required';
                    els.cloudStatus.style.color = '#9e9e9e';
                } else if (syncStatus.hasError) {
                    els.cloudStatus.innerText = '🔴 Error';
                    els.cloudStatus.classList.add('sync-error');
                } else {
                    els.cloudStatus.innerText = `● ${syncStatus.status}`;
                }
            }
            if (els.cloudConnection) els.cloudConnection.innerText = syncStatus.connection || 'Unknown';
            if (els.cloudPending) els.cloudPending.innerText = syncStatus.pendingQueue || '0';

            if (els.cloudLastSync) {
                if (syncStatus.lastSync > 0) {
                    const diff = Math.floor((Date.now() - syncStatus.lastSync) / 1000);
                    if (diff < 60) {
                        els.cloudLastSync.innerText = `${diff} seconds ago`;
                    } else if (diff < 3600) {
                        els.cloudLastSync.innerText = `${Math.floor(diff / 60)} minutes ago`;
                    } else {
                        els.cloudLastSync.innerText = new Date(syncStatus.lastSync).toLocaleString();
                    }
                } else {
                    els.cloudLastSync.innerText = 'Never';
                }
            }

            if (els.cloudRecords) els.cloudRecords.innerText = syncStatus.cloudRecords || '0';
            if (els.cloudLocalRecords) els.cloudLocalRecords.innerText = syncStatus.localRecords || '0';
        }

    } catch (e) {
        console.error("fetchDbStats error", e);
        if (els.totalTxns) els.totalTxns.innerText = 'Unavailable';
        if (els.dbSize) els.dbSize.innerText = 'Unavailable';
        if (els.lastBackup) els.lastBackup.innerText = 'Unavailable';
        if (els.cloudStatus) {
            els.cloudStatus.className = 'db-center-metric-value db-center-sync-badge sync-error';
            els.cloudStatus.innerText = '● Error';
        }
    }

    // Last Export — client-side tracked (no backend API for this)
    if (els.lastExport) {
        els.lastExport.innerText = lastExportTime || 'Never';
    }
}

/**
 * Fetches backup lifecycle statistics from AndroidBridge.getLifecycleStats().
 * Populates the new DB Center metrics: total backups, storage, health, breakdown.
 */
function fetchLifecycleStats() {
    const els = {
        totalBackups:     document.getElementById('dbcenter-total-backups'),
        backupStorage:    document.getElementById('dbcenter-backup-storage'),
        backupHealth:     document.getElementById('dbcenter-backup-health'),
        backupBreakdown:  document.getElementById('dbcenter-backup-breakdown'),
    };

    if (window.AndroidBridge && typeof window.AndroidBridge.getLifecycleStats === 'function') {
        try {
            const statsJson = window.AndroidBridge.getLifecycleStats();
            const stats = JSON.parse(statsJson);

            // Total Backups
            if (els.totalBackups) {
                els.totalBackups.innerText = (stats.totalBackups || 0).toLocaleString();
            }

            // Backup Storage
            if (els.backupStorage) {
                const bytes = parseInt(stats.totalBackupStorageBytes, 10) || 0;
                if (bytes >= 1048576) {
                    els.backupStorage.innerText = `${(bytes / 1048576).toFixed(1)} MB`;
                } else if (bytes >= 1024) {
                    els.backupStorage.innerText = `${(bytes / 1024).toFixed(0)} KB`;
                } else {
                    els.backupStorage.innerText = `${bytes} B`;
                }
            }

            // Backup Health
            if (els.backupHealth) {
                els.backupHealth.className = 'db-center-metric-value db-center-health-badge';
                const healthStatus = stats.healthStatus || 'neutral';
                const healthLabel = stats.healthLabel || 'Unknown';
                if (healthStatus === 'ok') {
                    els.backupHealth.innerText = '● ' + healthLabel;
                    els.backupHealth.classList.add('health-ok');
                } else if (healthStatus === 'warning') {
                    els.backupHealth.innerText = '● ' + healthLabel;
                    els.backupHealth.classList.add('health-warning');
                } else {
                    els.backupHealth.innerText = healthLabel;
                    els.backupHealth.classList.add('health-neutral');
                }
            }

            // Breakdown: Auto / Manual / Emergency
            if (els.backupBreakdown) {
                const a = stats.autoBackupCount || 0;
                const m = stats.manualBackupCount || 0;
                const e = stats.emergencyBackupCount || 0;
                els.backupBreakdown.innerText = `${a} / ${m} / ${e}`;
            }
        } catch (e) {
            console.error('fetchLifecycleStats error', e);
            if (els.totalBackups)    els.totalBackups.innerText = 'Unavailable';
            if (els.backupStorage)   els.backupStorage.innerText = 'Unavailable';
            if (els.backupHealth)    els.backupHealth.innerText = 'Unavailable';
            if (els.backupBreakdown) els.backupBreakdown.innerText = 'Unavailable';
        }
    } else {
        // Desktop mode — no lifecycle stats
        if (els.totalBackups)    els.totalBackups.innerText = '—';
        if (els.backupStorage)   els.backupStorage.innerText = '—';
        if (els.backupHealth)    els.backupHealth.innerText = '—';
        if (els.backupBreakdown) els.backupBreakdown.innerText = '—';
    }
}

// ═══════════════════════════════════════════════════════════════════
// NEW MOBILE FORM INTERACTIONS
// ═══════════════════════════════════════════════════════════════════

document.addEventListener('DOMContentLoaded', () => {
    // Restore Selected logic
    console.log('[RESTORE] DOMContentLoaded: doRestoreSelectedBtn exists:', !!doRestoreSelectedBtn);
    console.log('[RESTORE] DOMContentLoaded: availableBackupsSelect exists:', !!availableBackupsSelect);
    if (doRestoreSelectedBtn && availableBackupsSelect) {
        console.log('[RESTORE] wiring up doRestoreSelectedBtn click handler');
        doRestoreSelectedBtn.addEventListener('click', () => {
            console.log('[RESTORE] do-restore-selected-btn clicked');
            const selectedFileName = availableBackupsSelect.value;
            console.log('[RESTORE] selected backup:', selectedFileName);
            if (!selectedFileName) {
                console.log('[RESTORE] no backup selected, showing alert');
                alert("Please select a backup to restore.");
                return;
            }

            if (!confirm(`Restore selected backup (${selectedFileName})?\n\nCurrent database will be replaced.\nAn emergency backup will be created first.`)) {
                console.log('[RESTORE] user cancelled confirm dialog');
                return;
            }

            console.log('[RESTORE] user confirmed, checking AndroidBridge');
            if (window.AndroidBridge && typeof window.AndroidBridge.restoreDatabase === 'function') {
                try {
                    console.log('[RESTORE] bridge entered, calling restoreDatabase:', selectedFileName);
                    doRestoreSelectedBtn.querySelector('.btn-loader').classList.remove('hidden');
                    doRestoreSelectedBtn.querySelector('.btn-text').textContent = 'Restoring...';

                    console.log('[RESTORE] restore started');
                    const responseJson = window.AndroidBridge.restoreDatabase(selectedFileName);
                    console.log('[RESTORE] raw restore response:', responseJson);
                    const response = JSON.parse(responseJson);
                    console.log('[RESTORE] parsed restore response:', JSON.stringify(response));

                    if (response.status === 'success') {
                        console.log('[RESTORE] restore completed successfully');
                        alert("Restore successful! App will now reload.");
                        window.location.reload();
                    } else {
                        console.log('[RESTORE] restore failed:', response.message);
                        alert("Restore failed: " + (response.message || "Unknown error"));
                        doRestoreSelectedBtn.querySelector('.btn-loader').classList.add('hidden');
                        doRestoreSelectedBtn.querySelector('.btn-text').textContent = 'Restore Selected';
                    }
                } catch (e) {
                    console.error('[RESTORE] restore exception:', e);
                    alert("Restore error: " + e.message);
                    doRestoreSelectedBtn.querySelector('.btn-loader').classList.add('hidden');
                    doRestoreSelectedBtn.querySelector('.btn-text').textContent = 'Restore Selected';
                }
            } else {
                console.log('[RESTORE] no AndroidBridge.restoreDatabase, showing alert');
                alert("Restore is only available on Android app.");
            }
        });
    } else {
        console.log('[RESTORE] DEAD END: doRestoreSelectedBtn or availableBackupsSelect is null, restore click handler NOT wired');
    }

    // Segmented Toggle Logic
    const toggleBtns = document.querySelectorAll('.toggle-btn');
    const typeSelect = document.getElementById('type');

    toggleBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            toggleBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            if (typeSelect) {
                typeSelect.value = btn.dataset.value;
            }
        });
    });

    // Quick Chips Logic
    const chips = document.querySelectorAll('.chip');
    const amountInput = document.getElementById('amount');

    chips.forEach(chip => {
        chip.addEventListener('click', () => {
            if (amountInput) {
                const currentVal = parseFloat(amountInput.value) || 0;
                const addVal = parseFloat(chip.dataset.amount);
                amountInput.value = (currentVal + addVal).toFixed(2);
            }
        });
    });

    // Collapsible Details Logic
    const detailsToggle = document.getElementById('details-toggle');
    if (detailsToggle) {
        detailsToggle.addEventListener('click', () => {
            detailsToggle.classList.toggle('open');
        });
    }

    // Cancel Button Logic
    const cancelBtn = document.getElementById('cancel-btn');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', () => {
            if (isMobile()) {
                closeMobileForm();
            } else {
                closeModal();
            }
        });
    }
});

// Update toggle buttons when form is populated (e.g. edit mode)
const originalPopulateForm = populateForm;
populateForm = function(mode, data) {
    originalPopulateForm(mode, data);

    // Sync UI with selected type
    const typeSelect = document.getElementById('type');
    if (typeSelect) {
        const toggleBtns = document.querySelectorAll('.toggle-btn');
        toggleBtns.forEach(btn => {
            if (btn.dataset.value === typeSelect.value) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });
    }
};

// Reset Details section when form is reset
const originalResetForm = resetForm;
resetForm = function() {
    originalResetForm();
    const detailsToggle = document.getElementById('details-toggle');
    if (detailsToggle) {
        detailsToggle.classList.remove('open');
    }
    const toggleBtns = document.querySelectorAll('.toggle-btn');
    toggleBtns.forEach(btn => {
        if (btn.dataset.value === 'Debit') {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
};
