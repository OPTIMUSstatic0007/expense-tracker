const API_BASE_URL = '/transactions';
const EXPORT_BASE_URL = '/export';
const BACKUP_BASE_URL = '/backup';

// State Management
let transactions = [];
let editingId = null;
let currentPage = 1;
let hasMore = false;
let isLoadingMore = false;

// DOM Elements
const form = document.getElementById('exp-form');
const historyBody = document.getElementById('history-body');
const recordCountEl = document.getElementById('record-count');
const saveBtn = document.getElementById('save-btn');
const emptyStateEl = document.getElementById('empty-state');
const toastContainer = document.getElementById('toast-container');
const loadMoreBtn = document.getElementById('load-more-btn');
const loadMoreContainer = document.getElementById('load-more-container');

// Modal Elements - Direct Viewport Overlays
const modalWrapper = document.getElementById('transaction-modal');
const modalBackdrop = document.getElementById('modal-backdrop');
const modalClose = document.getElementById('modal-close');
const modalTitle = document.getElementById('modal-title');
const fabAdd = document.getElementById('fab-add');
const saveBtnText = document.getElementById('save-btn-text');

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

    // Export Handlers
    if (exportExcelBtn) exportExcelBtn.addEventListener('click', () => triggerExport('excel', exportExcelBtn));
    if (exportCsvBtn) exportCsvBtn.addEventListener('click', () => triggerExport('csv', exportCsvBtn));

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

    // Modal Events - Critical Binding
    if (fabAdd) fabAdd.addEventListener('click', () => openModal('create'));
    if (modalClose) modalClose.addEventListener('click', () => closeModal());
    if (modalBackdrop) modalBackdrop.addEventListener('click', () => closeModal());

    // Desktop Close on ESC
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && modalWrapper && !modalWrapper.classList.contains('hidden')) {
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
}

/**
 * Structural Fix: Modal Control logic
 */
function openModal(mode, data = null) {
    console.log("Opening Modal | Mode:", mode);
    if (!modalWrapper || !modalBackdrop) {
        console.error("Modal elements missing from DOM!");
        return;
    }

    if (mode === 'edit' && data) {
        modalTitle.innerText = 'Edit Entry';
        if (saveBtnText) saveBtnText.innerText = 'Update Ledger Entry';

        document.getElementById('date').value = data.date;
        document.getElementById('type').value = data.entryType;
        document.getElementById('amount').value = data.amount;
        document.getElementById('category').value = data.category;
        document.getElementById('source').value = data.expenseType;
        document.getElementById('person').value = data.paidTo;
        document.getElementById('notes').value = data.notes;
        editingId = data.id;
    } else {
        modalTitle.innerText = 'Record New Entry';
        if (saveBtnText) saveBtnText.innerText = 'Save to Ledger';
        resetForm();
    }

    // Atomic visibility toggle
    modalBackdrop.classList.remove('hidden');
    modalWrapper.classList.remove('hidden');

    console.log("Modal Wrapper Class:", modalWrapper.className);
    console.log("Modal Card visible?", !!document.querySelector('.modal-card')?.offsetParent);
    console.log("Form attached?", !!document.getElementById('exp-form'));

    // Background Lock
    document.body.style.overflow = 'hidden';

    // --- TEMPORARY ANDROID WEBVIEW DIAGNOSTICS ---
    setTimeout(() => {
        const card = document.querySelector('.modal-card');
        if (!card || !modalWrapper) return;
        const rect = card.getBoundingClientRect();
        const style = window.getComputedStyle(card);
        const wRect = modalWrapper.getBoundingClientRect();
        
        console.log("=== MODAL RUNTIME DIAGNOSTICS ===");
        console.log("Window Size:", window.innerWidth, "x", window.innerHeight);
        console.log("Wrapper Rect:", JSON.stringify({top: wRect.top, bottom: wRect.bottom, height: wRect.height}));
        console.log("Card Rect:", JSON.stringify({top: rect.top, bottom: rect.bottom, height: rect.height, y: rect.y}));
        console.log("Card OffsetHeight:", card.offsetHeight);
        console.log("Card OffsetTop:", card.offsetTop);
        console.log("Computed Position:", style.position);
        console.log("Computed Transform:", style.transform);
        console.log("Computed Opacity:", style.opacity);
        console.log("Computed Visibility:", style.visibility);
        console.log("Computed Display:", style.display);
        console.log("Computed Z-Index:", style.zIndex);
        console.log("=================================");
    }, 500);
}

function closeModal() {
    if (!modalWrapper || !modalBackdrop) return;

    modalWrapper.classList.add('hidden');
    modalBackdrop.classList.add('hidden');

    // Background Unlock
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
        const response = await fetch(`${API_BASE_URL}?page=${currentPage}&limit=30`);
        if (!response.ok) throw new Error('Fetch failed');
        const data = await response.json();

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
                            <button class="btn-action-mobile" onclick="event.stopPropagation(); editTransaction(${t.id})">Edit Entry</button>
                            <button class="btn-action-mobile" onclick="event.stopPropagation(); deleteTransaction(${t.id})">Delete</button>
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
                        <button class="btn-action btn-edit" onclick="editTransaction(${t.id})">Edit</button>
                        <button class="btn-action btn-delete" onclick="deleteTransaction(${t.id})">Del</button>
                    </div>
                </td>
            `;
        }
        historyBody.appendChild(row);
    });
}

function updateDashboard(filtered, selMonth, selYear, pagedData = null) {
    if (pagedData) {
        balanceEl.innerText = currencyFormatter.format(pagedData.globalBalance);
        creditEl.innerText = currencyFormatter.format(pagedData.totalCredit);
        debitEl.innerText = currencyFormatter.format(pagedData.totalDebit);
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
            const response = await fetch(editingId ? `${API_BASE_URL}/${editingId}` : API_BASE_URL, {
                method: editingId ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            if (response.ok) {
                showToast(editingId ? 'Entry updated' : 'Entry added');
                closeModal();
                currentPage = 1;
                loadTransactions();
                updateBackupStatus();
            } else {
                const err = await response.text();
                showToast('Save failed: ' + (err || 'Server error'), 'error');
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
    } catch (e) {
        showToast('Delete failed', 'error');
    }
}

function editTransaction(id) {
    const t = transactions.find(x => x.id === id);
    if (!t) return;
    openModal('edit', t);
}

function triggerExport(format, btn) {
    const originalText = format === 'excel' ? 'Excel' : 'CSV';
    setLoading(btn, true, originalText);
    setTimeout(() => {
        window.location.href = `${EXPORT_BASE_URL}/${format}?${new URLSearchParams({ month: filterMonth.value, year: filterYear.value, category: filterCategory.value, search: searchInput.value }).toString()}`;
        setLoading(btn, false, originalText);
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
