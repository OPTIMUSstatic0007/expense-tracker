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
// ═══════════════════════════════════════════════════════════════════
function initTheme() {
    const savedTheme = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;

    if (savedTheme === 'dark' || (!savedTheme && prefersDark)) {
        document.documentElement.setAttribute('data-theme', 'dark');
    } else {
        document.documentElement.removeAttribute('data-theme');
    }
}

function toggleTheme() {
    const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    if (isDark) {
        document.documentElement.removeAttribute('data-theme');
        localStorage.setItem('theme', 'light');
    } else {
        document.documentElement.setAttribute('data-theme', 'dark');
        localStorage.setItem('theme', 'dark');
    }

    // Refresh to pick up new theme colors in charts
    if (typeof refreshData === 'function') {
        refreshData();
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
        setLoading(snapshotBtn, true, 'Create Restore Point');
        if (backupStatusEl) {
            backupStatusEl.innerText = 'Creating Snapshot';
            backupStatusEl.className = 'status-working';
        }
        try {
            const response = await fetch(`${BACKUP_BASE_URL}/snapshot`, { method: 'POST' });
            if (response.ok) {
                if (backupStatusEl) {
                    backupStatusEl.innerText = 'Restore Point Created';
                    backupStatusEl.className = 'status-manual';
                }
                showToast('Manual restore point created');
                setTimeout(updateBackupStatus, 2000);
            } else {
                showToast('Failed to create snapshot', 'error');
                updateBackupStatus();
            }
        } catch (e) {
            showToast('Network error', 'error');
            updateBackupStatus();
        } finally {
            setLoading(snapshotBtn, false, 'Create Restore Point');
        }
    });

    if (backupBtn) backupBtn.addEventListener('click', () => {
        showToast('Preparing database backup...', 'info');
        window.location.href = `${BACKUP_BASE_URL}/database`;
        setTimeout(updateBackupStatus, 2000);
    });

    if (restoreBtn) restoreBtn.addEventListener('click', () => restoreInput.click());

    if (restoreInput) restoreInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) handleRestore(e.target.files[0]);
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
        drawerSnapshotBtn.addEventListener('click', async () => {
            const textEl  = drawerSnapshotBtn.querySelector('.db-center-action-text');
            const loader  = drawerSnapshotBtn.querySelector('.drawer-action-loader');
            const origText = 'Create Restore Point';

            drawerSnapshotBtn.disabled = true;
            if (textEl) textEl.innerText = 'Creating...';
            if (loader) loader.classList.remove('hidden');

            try {
                const response = await fetch(`${BACKUP_BASE_URL}/snapshot`, { method: 'POST' });
                if (response.ok) {
                    showToast('Restore point created');
                    updateBackupStatus();
                    fetchDbStats();
                } else {
                    showToast('Failed to create restore point', 'error');
                }
            } catch (e) {
                showToast('Network error', 'error');
            } finally {
                drawerSnapshotBtn.disabled = false;
                if (textEl) textEl.innerText = origText;
                if (loader) loader.classList.add('hidden');
            }
        });
    }

    // 2. Backup Database — reuses existing /backup/database download
    const drawerBackupBtn = document.getElementById('drawer-backup-btn');
    if (drawerBackupBtn) {
        drawerBackupBtn.addEventListener('click', () => {
            showToast('Preparing database backup...', 'info');
            window.location.href = `${BACKUP_BASE_URL}/database`;
            setTimeout(() => {
                updateBackupStatus();
                fetchDbStats();
            }, 2000);
        });
    }

    // 3. Restore Database — triggers existing file input
    const drawerRestoreBtn = document.getElementById('drawer-restore-btn');
    if (drawerRestoreBtn && restoreInput) {
        drawerRestoreBtn.addEventListener('click', () => {
            closeDbCenter();
            // Small delay lets panel close animation finish before file picker opens
            setTimeout(() => restoreInput.click(), 350);
        });
    }

    // 4. Cleanup Old Records — placeholder (no backend API)
    const drawerCleanupBtn = document.getElementById('drawer-cleanup-btn');
    if (drawerCleanupBtn) {
        drawerCleanupBtn.addEventListener('click', () => {
            showToast('Cleanup feature coming soon', 'info');
        });
    }

    // 5. Import Legacy Data — only visible on Android bridge + not yet imported
    const drawerImportBtn = document.getElementById('drawer-import-legacy-btn');
    if (drawerImportBtn && window.AndroidBridge) {
        // Check import status first — reveal button only if not yet done
        try {
            const statusRaw = window.AndroidBridge.checkImportStatus();
            const status = JSON.parse(statusRaw);
            if (!status.imported) {
                drawerImportBtn.classList.remove('hidden');
            }
        } catch (e) {
            // If status check fails, leave button hidden — safe default
            console.warn('Import status check failed:', e);
        }

        drawerImportBtn.addEventListener('click', async () => {
            const textEl  = document.getElementById('import-legacy-btn-text');
            const loader  = document.getElementById('import-legacy-loader');
            const origText = 'Import Legacy Data';

            if (!confirm('Import 64 historical records from the legacy ledger?\n\nThis action is one-time and cannot be undone. The database must be empty.')) {
                return;
            }

            drawerImportBtn.disabled = true;
            if (textEl) textEl.innerText = 'Importing...';
            if (loader) loader.classList.remove('hidden');

            try {
                // importLegacyDatabase() is synchronous on the bridge thread
                // but JS sees it as a regular return value (no await needed)
                const resultRaw = window.AndroidBridge.importLegacyDatabase();
                const result = JSON.parse(resultRaw);

                switch (result.status) {
                    case 'success':
                        showToast(`Import complete: ${result.importedCount} records, balance ₹${parseFloat(result.finalBalance).toFixed(2)}`, 'success');
                        drawerImportBtn.classList.add('hidden'); // Never show again
                        closeDbCenter();
                        currentPage = 1;
                        loadTransactions(false); // Refresh ledger with imported data
                        break;
                    case 'alreadyImported':
                        showToast('Legacy data was already imported previously.', 'info');
                        drawerImportBtn.classList.add('hidden');
                        break;
                    case 'roomNotEmpty':
                        showToast(`Import aborted: database already has ${result.existingCount} records.`, 'error');
                        break;
                    case 'legacyDbNotFound':
                        showToast('Legacy database file not found. Contact support.', 'error');
                        console.error('Legacy DB not found:', result.reason);
                        break;
                    case 'unexpectedCount':
                        showToast(`Import failed: expected 64 records, found ${result.actual}.`, 'error');
                        break;
                    case 'validationFailed':
                        showToast(`Validation failed (${result.gate}): ${result.detail}`, 'error');
                        console.error('Import validation failed:', result);
                        break;
                    default:
                        showToast(`Import error: ${result.error || 'Unknown error'}`, 'error');
                        console.error('Import error:', result);
                }
            } catch (e) {
                showToast('Import failed due to unexpected error.', 'error');
                console.error('importLegacyDatabase() exception:', e);
            } finally {
                drawerImportBtn.disabled = false;
                if (textEl) textEl.innerText = origText;
                if (loader) loader.classList.add('hidden');
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

    // Sync Status — from existing /backup/status API
    try {
        const response = await fetch(`${BACKUP_BASE_URL}/status`);
        if (response.ok) {
            const status = await response.json();

            if (els.syncStatus) {
                els.syncStatus.className = 'drawer-status-value drawer-sync-indicator';
                if (status.status === 'Synced') {
                    els.syncStatus.innerText = '● Synced';
                    els.syncStatus.classList.add('sync-ok');
                } else if (status.status === 'Pending Sync') {
                    els.syncStatus.innerText = `● Pending (${status.transactionsSinceLast || 0}/10)`;
                    els.syncStatus.classList.add('sync-pending');
                } else {
                    els.syncStatus.innerText = status.status || 'Unknown';
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
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `<span class="toast-msg">${message}</span>`;
    toastContainer.appendChild(toast);
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
        const response = await fetch(`${BACKUP_BASE_URL}/status`);
        if (response.ok) {
            const status = await response.json();

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

async function handleRestore(file) {
    if (!confirm('WARNING: This will replace your current ledger data. An emergency backup will be created first. Proceed?')) {
        restoreInput.value = '';
        return;
    }

    setLoading(restoreBtn, true, 'Restore from File');
    const formData = new FormData();
    formData.append('file', file);

    try {
        const response = await fetch('/restore/database', {
            method: 'POST',
            body: formData
        });

        if (response.ok) {
            showToast('Database restored successfully');
            currentPage = 1;
            await loadTransactions();
            updateBackupStatus();
        } else {
            const err = await response.text();
            showToast(err || 'Failed to restore', 'error');
        }
    } catch (error) {
        showToast('Network error during restore', 'error');
    } finally {
        setLoading(restoreBtn, false, 'Restore from File');
        restoreInput.value = '';
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
        const response = await fetch(`${API_BASE_URL}/all`);
        if (!response.ok) throw new Error('Failed to fetch analytics data');
        const data = await response.json();

        processAnalyticsData(data);
    } catch (e) {
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
        syncStatus: document.getElementById('dbcenter-sync-status'),
    };

    try {
        const response = await fetch('/db/stats');
        if (response.ok) {
            const stats = await response.json();

            // Total Transactions — REAL backend count
            if (els.totalTxns) {
                const count = parseInt(stats.totalTransactions, 10) || 0;
                els.totalTxns.innerText = count.toLocaleString();
            }

            // Database Size — REAL file size from backend
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

            // Last Backup
            if (els.lastBackup) {
                els.lastBackup.innerText = stats.lastBackupTime || 'Never';
            }

            // Sync Status
            if (els.syncStatus) {
                els.syncStatus.className = 'db-center-metric-value db-center-sync-badge';
                if (stats.syncStatus === 'Synced') {
                    els.syncStatus.innerText = '● Synced';
                    els.syncStatus.classList.add('sync-ok');
                } else if (stats.syncStatus === 'Pending Sync') {
                    els.syncStatus.innerText = `● Pending (${stats.pendingMutations || 0}/10)`;
                    els.syncStatus.classList.add('sync-pending');
                } else {
                    els.syncStatus.innerText = stats.syncStatus || 'Unknown';
                }
            }
        }
    } catch (e) {
        // Graceful fallback — panel still shows but with placeholder data
        if (els.totalTxns) els.totalTxns.innerText = 'Unavailable';
        if (els.dbSize) els.dbSize.innerText = 'Unavailable';
        if (els.lastBackup) els.lastBackup.innerText = 'Unavailable';
        if (els.syncStatus) {
            els.syncStatus.className = 'db-center-metric-value db-center-sync-badge sync-error';
            els.syncStatus.innerText = '● Error';
        }
    }

    // Last Export — client-side tracked (no backend API for this)
    if (els.lastExport) {
        els.lastExport.innerText = lastExportTime || 'Never';
    }
}

// ═══════════════════════════════════════════════════════════════════
// NEW MOBILE FORM INTERACTIONS
// ═══════════════════════════════════════════════════════════════════

document.addEventListener('DOMContentLoaded', () => {
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
