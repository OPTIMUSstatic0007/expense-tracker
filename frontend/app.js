const API_BASE_URL = 'http://127.0.0.1:8080/transactions';
const EXPORT_BASE_URL = 'http://127.0.0.1:8080/export';

// State Management
let transactions = []; // Master list from backend
let editingId = null;

// DOM Elements
const form = document.getElementById('exp-form');
const historyBody = document.getElementById('history-body');
const recordCountEl = document.getElementById('record-count');
const saveBtn = document.getElementById('save-btn');

// Summary Dashboard Elements
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
    // Set default date and filter defaults
    const dateInput = document.getElementById('date');
    if (dateInput) dateInput.valueAsDate = new Date();

    // Set current year as default filter
    if (filterYear) filterYear.value = new Date().getFullYear().toString();

    loadTransactions();
    setupEventListeners();
});

function setupEventListeners() {
    // Real-time filtering
    [searchInput, filterMonth, filterYear, filterCategory, filterType].forEach(el => {
        if (el) el.addEventListener('input', () => applyFilters());
    });

    // Export Actions
    if (exportExcelBtn) exportExcelBtn.addEventListener('click', () => triggerExport('excel'));
    if (exportCsvBtn) exportCsvBtn.addEventListener('click', () => triggerExport('csv'));
}

const currencyFormatter = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
});

// A. Load Transactions (GET)
async function loadTransactions() {
    try {
        const response = await fetch(API_BASE_URL);
        if (!response.ok) throw new Error('Failed to fetch');
        transactions = await response.json();

        applyFilters(); // Initial render with filters
    } catch (error) {
        console.error('Error:', error);
        alert('Backend unreachable. Ensure Ktor is running on port 8080.');
    }
}

// B. Apply Filtering & Search Logic (Frontend)
function applyFilters() {
    const searchTerm = searchInput.value.toLowerCase();
    const month = filterMonth.value;
    const year = filterYear.value;
    const category = filterCategory.value;
    const type = filterType.value;

    const filtered = transactions.filter(t => {
        // Search matches: category, paidTo, or notes
        const matchesSearch =
            t.category.toLowerCase().includes(searchTerm) ||
            (t.paidTo && t.paidTo.toLowerCase().includes(searchTerm)) ||
            (t.notes && t.notes.toLowerCase().includes(searchTerm));

        // Date parsing (YYYY-MM-DD)
        const [tYear, tMonth] = t.date.split('-');
        const matchesMonth = month === "" || tMonth === month;
        const matchesYear = year === "" || tYear === year;
        const matchesCategory = category === "" || t.category === category;
        const matchesType = type === "" || t.entryType === type;

        return matchesSearch && matchesMonth && matchesYear && matchesCategory && matchesType;
    });

    renderTable(filtered);
    updateDashboard(filtered, month, year);
}

// Render Table Rows
function renderTable(dataToRender) {
    if (!historyBody) return;
    historyBody.innerHTML = '';
    recordCountEl.innerText = `${dataToRender.length} Records`;

    if (dataToRender.length === 0) {
        historyBody.innerHTML = '<tr><td colspan="8" class="text-center" style="padding: 48px; color: var(--text-muted);">No records found matching the filters.</td></tr>';
        return;
    }

    dataToRender.forEach(t => {
        const isCredit = t.entryType === 'Credit';
        const amount = parseFloat(t.amount);
        const balanceAfter = parseFloat(t.balanceAfter);

        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${t.date}</td>
            <td>
                <div class="category-cell">${escapeHtml(t.category)}</div>
                <div class="paid-to-text">${escapeHtml(t.paidTo || '-')}</div>
            </td>
            <td>${escapeHtml(t.expenseType)}</td>
            <td>
                <span class="status-pill ${isCredit ? 'status-added' : 'status-expense'}">
                    ${isCredit ? 'ADDED' : 'EXPENSE'}
                </span>
            </td>
            <td class="text-right" style="font-weight: 500;">
                ${!isCredit ? amount.toFixed(2) : '—'}
            </td>
            <td class="text-right" style="font-weight: 500;">
                ${isCredit ? amount.toFixed(2) : '—'}
            </td>
            <td class="text-right" style="font-weight: 600; color: var(--text-main);">
                ${balanceAfter.toFixed(2)}
            </td>
            <td class="text-center">
                <div style="display: flex; gap: 4px; justify-content: center;">
                    <button class="btn-action btn-edit" onclick="editTransaction(${t.id})">Edit</button>
                    <button class="btn-action btn-delete" onclick="deleteTransaction(${t.id})">Del</button>
                </div>
            </td>
        `;
        historyBody.appendChild(row);
    });
}

// Update Dashboard Stats (Global & Monthly)
function updateDashboard(filtered, selectedMonth, selectedYear) {
    const globalCredit = transactions
        .filter(t => t.entryType === 'Credit')
        .reduce((s, t) => s + parseFloat(t.amount), 0);

    const globalDebit = transactions
        .filter(t => t.entryType === 'Debit')
        .reduce((s, t) => s + parseFloat(t.amount), 0);

    const globalInHand = transactions.length > 0
        ? parseFloat(transactions[transactions.length - 1].balanceAfter)
        : 0;

    balanceEl.innerText = currencyFormatter.format(globalInHand);
    creditEl.innerText = currencyFormatter.format(globalCredit);
    debitEl.innerText = currencyFormatter.format(globalDebit);

    const periodCredit = filtered
        .filter(t => t.entryType === 'Credit')
        .reduce((s, t) => s + parseFloat(t.amount), 0);

    const periodDebit = filtered
        .filter(t => t.entryType === 'Debit')
        .reduce((s, t) => s + parseFloat(t.amount), 0);

    const periodBalance = periodCredit - periodDebit;

    monthBalanceEl.innerText = (periodBalance >= 0 ? '+' : '') + currencyFormatter.format(periodBalance);
    monthBalanceEl.style.color = periodBalance >= 0 ? 'var(--primary)' : 'var(--danger)';

    if (selectedMonth || selectedYear) {
        const monthName = selectedMonth ? filterMonth.options[filterMonth.selectedIndex].text : '';
        currentViewLabel.innerText = `Viewing: ${monthName} ${selectedYear}`.trim();
    } else {
        currentViewLabel.innerText = 'Viewing: All-time Transactions';
    }
}

// C. CRUD Operations
form.addEventListener('submit', async (e) => {
    e.preventDefault();

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
        const url = editingId ? `${API_BASE_URL}/${editingId}` : API_BASE_URL;
        const method = editingId ? 'PUT' : 'POST';
        const response = await fetch(url, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });

        if (response.ok) {
            resetForm();
            loadTransactions();
        }
    } catch (error) { console.error('Save error:', error); }
});

async function deleteTransaction(id) {
    if (!confirm('Delete this entry?')) return;
    try {
        const response = await fetch(`${API_BASE_URL}/${id}`, { method: 'DELETE' });
        if (response.ok) loadTransactions();
    } catch (error) { console.error('Delete error:', error); }
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
    saveBtn.innerText = 'Update Entry';
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function triggerExport(format) {
    const month = filterMonth.value;
    const year = filterYear.value;
    const category = filterCategory.value;
    const search = searchInput.value;

    const params = new URLSearchParams({
        month: month,
        year: year,
        category: category,
        search: search
    });

    const exportUrl = `${EXPORT_BASE_URL}/${format}?${params.toString()}`;
    window.location.href = exportUrl;
}

function resetForm() {
    form.reset();
    editingId = null;
    saveBtn.innerText = 'Save to Ledger';
    const dateInput = document.getElementById('date');
    if (dateInput) dateInput.valueAsDate = new Date();
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
