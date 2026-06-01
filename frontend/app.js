const API_BASE_URL = '/transactions';
const EXPORT_BASE_URL = '/export';
const BACKUP_BASE_URL = '/backup';

// State Management
let transactions = [];
let editingId = null;

// DOM Elements
const form = document.getElementById('exp-form');
const historyBody = document.getElementById('history-body');
const recordCountEl = document.getElementById('record-count');
const saveBtn = document.getElementById('save-btn');
const emptyStateEl = document.getElementById('empty-state');
const toastContainer = document.getElementById('toast-container');

// Management Elements
const backupBtn = document.getElementById('backup-db-btn');
const restoreBtn = document.getElementById('restore-db-btn');
const restoreInput = document.getElementById('restore-input');
const backupStatusEl = document.getElementById('backup-status');
const lastBackupTimeEl = document.getElementById('last-backup-time');
const backupCountEl = document.getElementById('backup-count');

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
    [searchInput, filterMonth, filterYear, filterCategory, filterType].forEach(el => {
        if (el) el.addEventListener('input', () => applyFilters());
    });

    window.addEventListener('resize', () => {
        const isMobileNow = window.innerWidth <= 768;
        if (window.lastWasMobile !== isMobileNow) {
            window.lastWasMobile = isMobileNow;
            applyFilters();
        }
    });

    if (exportExcelBtn) exportExcelBtn.addEventListener('click', () => triggerExport('excel', exportExcelBtn));
    if (exportCsvBtn) exportCsvBtn.addEventListener('click', () => triggerExport('csv', exportCsvBtn));

    // Backup & Restore
    if (backupBtn) backupBtn.addEventListener('click', () => {
        showToast('Preparing database backup...', 'info');
        window.location.href = `${BACKUP_BASE_URL}/database`;
        setTimeout(updateBackupStatus, 2000);
    });

    if (restoreBtn) restoreBtn.addEventListener('click', () => restoreInput.click());

    if (restoreInput) restoreInput.addEventListener('change', (e) => {
        if (e.target.files.length > 0) handleRestore(e.target.files[0]);
    });

    // Real-time validation
    form.querySelectorAll('input, select').forEach(input => {
        input.addEventListener('blur', () => validateField(input));
    });
}

const currencyFormatter = new Intl.NumberFormat('en-IN', {
    style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2
});

function formatMobileDate(dateStr) {
    try {
        if (!dateStr) return '-';
        const parts = dateStr.split('-');
        if (parts.length < 3) return dateStr;
        const [y, m, d] = parts;
        const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        return `${d} ${months[parseInt(m) - 1]}`;
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
        loader?.classList.remove('hidden');
        if (textSpan) textSpan.innerText = 'Working...';
    } else {
        loader?.classList.add('hidden');
        if (textSpan) textSpan.innerText = originalText;
    }
}

// --- Data Operations ---

async function loadTransactions() {
    try {
        const response = await fetch(API_BASE_URL);
        if (!response.ok) throw new Error('Fetch failed');
        transactions = await response.json();

        // Auto-select current year only if it has records
        const currentYear = new Date().getFullYear().toString();
        const hasCurrentYearRecords = transactions.some(t => t.date.startsWith(currentYear));
        if (hasCurrentYearRecords && filterYear) {
            filterYear.value = currentYear;
        }

        applyFilters();
    } catch (error) {
        showToast('Connection failed: Server unreachable', 'error');
        console.error(error);
    }
}

async function updateBackupStatus() {
    try {
        const response = await fetch(`${BACKUP_BASE_URL}/status`);
        if (response.ok) {
            const status = await response.json();
            if (backupStatusEl) backupStatusEl.innerText = status.status;
            if (lastBackupTimeEl) lastBackupTimeEl.innerText = status.lastBackupTime;
            if (backupCountEl) backupCountEl.innerText = status.backupCount;
        }
    } catch (e) {
        console.error("Status check failed", e);
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

        if (isMobile) {
            // Optimized Compact DOM with Accordion for Mobile
            row.innerHTML = `
                <div class="row-compact" onclick="toggleRow(this)">
                    <td data-label="Date">
                        <span class="mobile-date">${formatMobileDate(t.date)}</span>
                    </td>
                    <td data-label="Transaction">
                        <div class="row-main">
                            <div class="transaction-info">
                                <div class="category-cell">${escapeHtml(t.category)}</div>
                            </div>
                            <div class="mobile-amount ${isCredit ? 'credit-text' : 'debit-text'}">
                                ${isCredit ? '+' : '-'}₹${parseFloat(t.amount).toFixed(2)}
                            </div>
                            <svg class="chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                                <polyline points="6 9 12 15 18 9"></polyline>
                            </svg>
                        </div>
                    </td>
                </div>
                <div class="row-details">
                    <div class="details-grid">
                        <div class="detail-item">
                            <span class="detail-label">Full Date</span>
                            <span class="detail-value">${t.date}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Entry Type</span>
                            <span class="detail-value">${t.entryType}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Paid To / From</span>
                            <span class="detail-value">${escapeHtml(t.paidTo || '—')}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Method</span>
                            <span class="detail-value">${escapeHtml(t.expenseType || '—')}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Balance After</span>
                            <span class="detail-value">₹${parseFloat(t.balanceAfter).toFixed(2)}</span>
                        </div>
                        <div class="detail-item detail-full-width">
                            <span class="detail-label">Notes</span>
                            <span class="detail-value">${escapeHtml(t.notes || 'No reference notes')}</span>
                        </div>
                    </div>
                    <div class="details-actions">
                        <button class="btn-action btn-edit" onclick="event.stopPropagation(); editTransaction(${t.id})">Edit Entry</button>
                        <button class="btn-action btn-delete" onclick="event.stopPropagation(); deleteTransaction(${t.id})">Delete</button>
                    </div>
                </div>
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

function updateDashboard(filtered, selMonth, selYear) {
    const globalInHand = transactions.length > 0 ? parseFloat(transactions[transactions.length - 1].balanceAfter) : 0;
    balanceEl.innerText = currencyFormatter.format(globalInHand);
    creditEl.innerText = currencyFormatter.format(transactions.filter(t => t.entryType === 'Credit').reduce((s, t) => s + parseFloat(t.amount), 0));
    debitEl.innerText = currencyFormatter.format(transactions.filter(t => t.entryType === 'Debit').reduce((s, t) => s + parseFloat(t.amount), 0));

    const periodBal = filtered.reduce((s, t) => s + (t.entryType === 'Credit' ? parseFloat(t.amount) : -parseFloat(t.amount)), 0);
    monthBalanceEl.innerText = (periodBal >= 0 ? '+' : '') + currencyFormatter.format(periodBal);
    monthBalanceEl.style.color = periodBal >= 0 ? 'var(--primary)' : 'var(--danger)';

    const monthName = selMonth ? filterMonth.options[filterMonth.selectedIndex].text : '';
    currentViewLabel.innerText = (selMonth || selYear) ? `Viewing: ${monthName} ${selYear}`.trim() : 'Viewing: All-time Transactions';
}

function toggleRow(element) {
    const row = element.parentElement;
    const isExpanded = row.classList.contains('tr-expanded');

    // Collapse all other rows
    document.querySelectorAll('.ledger-table tr').forEach(r => {
        if (r !== row) r.classList.remove('tr-expanded');
    });

    // Toggle current row
    if (isExpanded) {
        row.classList.remove('tr-expanded');
    } else {
        row.classList.add('tr-expanded');
        // Smooth scroll if row is near bottom
        setTimeout(() => {
            const rect = row.getBoundingClientRect();
            if (rect.bottom > window.innerHeight) {
                row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        }, 300);
    }
}

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
            resetForm();
            loadTransactions();
            updateBackupStatus();
        }
    } catch (e) { showToast('Error saving data', 'error'); }
    finally { setLoading(saveBtn, false, editingId ? 'Update Ledger Entry' : 'Save to Ledger'); }
});

async function deleteTransaction(id) {
    if (!confirm('Delete this entry?')) return;
    try {
        const response = await fetch(`${API_BASE_URL}/${id}`, { method: 'DELETE' });
        if (response.ok) {
            showToast('Deleted');
            loadTransactions();
            updateBackupStatus();
        }
    } catch (e) { showToast('Delete failed', 'error'); }
}

function editTransaction(id) {
    const t = transactions.find(x => x.id === id);
    if (!t) return;
    document.getElementById('date').value = t.date;
    document.getElementById('type').value = t.entryType;
    document.getElementById('amount').value = t.amount;
    document.getElementById('category').value = t.category;
    document.getElementById('source').value = t.expenseType;
    document.getElementById('person').value = t.paidTo;
    document.getElementById('notes').value = t.notes;
    editingId = id;
    saveBtn.querySelector('.btn-text').innerText = 'Update Ledger Entry';
    window.scrollTo({ top: 0, behavior: 'smooth' });
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
    form.querySelectorAll('input[required], select[required]').forEach(i => { if(!validateField(i)) valid = false; });
    return valid;
}

function resetForm() {
    form.reset();
    editingId = null;
    saveBtn.querySelector('.btn-text').innerText = 'Save to Ledger';
    document.getElementById('date').valueAsDate = new Date();
}

function escapeHtml(t) {
    if (!t) return '';
    const d = document.createElement('div');
    d.textContent = t;
    return d.innerHTML;
}
