// State management for Household Ledger
let transactions = [
    {
        id: 1,
        date: '2023-10-01',
        type: 'Credit',
        amount: 25000.00,
        category: 'Cash Deposit',
        source: 'Self',
        person: 'Home Fund',
        notes: 'Monthly household budget'
    },
    {
        id: 2,
        date: '2023-10-05',
        type: 'Debit',
        amount: 1200.00,
        category: 'Groceries',
        source: 'Cash',
        person: 'Local Vendor',
        notes: 'Vegetables and Milk'
    }
];

// DOM Elements
const form = document.getElementById('exp-form');
const historyBody = document.getElementById('history-body');
const balanceEl = document.getElementById('balance');
const creditEl = document.getElementById('total-credit');
const debitEl = document.getElementById('total-debit');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadTransactions();
});

// Indian Currency Formatter (₹)
const currencyFormatter = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2
});

// Main Load Function
function loadTransactions() {
    renderTable();
    updateSummary();
}

// Render Ledger Table
function renderTable() {
    historyBody.innerHTML = '';
    let runningBalance = 0;

    // Sort transactions by date
    const sortedTransactions = [...transactions].sort((a, b) => new Date(a.date) - new Date(b.date));

    sortedTransactions.forEach(t => {
        const isCredit = t.type === 'Credit';
        if (isCredit) {
            runningBalance += t.amount;
        } else {
            runningBalance -= t.amount;
        }

        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${t.date}</td>
            <td>
                <div style="font-weight: 500;">${t.category}</div>
                <div style="font-size: 0.75rem; color: var(--text-muted);">${t.person || ''}</div>
            </td>
            <td>${t.source}</td>
            <td><span class="stat-label" style="font-size: 0.7rem; color: ${isCredit ? 'var(--success)' : 'var(--danger)'}">${isCredit ? 'ADDED' : 'EXPENSE'}</span></td>
            <td class="text-right">${!isCredit ? t.amount.toFixed(2) : '-'}</td>
            <td class="text-right">${isCredit ? t.amount.toFixed(2) : '-'}</td>
            <td class="text-right" style="font-weight: 600;">${runningBalance.toFixed(2)}</td>
            <td class="text-center">
                <button class="btn-action btn-edit" onclick="editTransaction(${t.id})">Edit</button>
                <button class="btn-action btn-delete" onclick="deleteTransaction(${t.id})">Delete</button>
            </td>
        `;
        historyBody.appendChild(row);
    });
}

// Update Summary Statistics
function updateSummary() {
    const totalCredit = transactions
        .filter(t => t.type === 'Credit')
        .reduce((sum, t) => sum + t.amount, 0);

    const totalDebit = transactions
        .filter(t => t.type === 'Debit')
        .reduce((sum, t) => sum + t.amount, 0);

    const balance = totalCredit - totalDebit;

    balanceEl.innerText = currencyFormatter.format(balance);
    creditEl.innerText = currencyFormatter.format(totalCredit);
    debitEl.innerText = currencyFormatter.format(totalDebit);
}

// Add New Transaction
form.addEventListener('submit', (e) => {
    e.preventDefault();

    const amountVal = parseFloat(document.getElementById('amount').value);
    if (isNaN(amountVal)) return;

    const newTransaction = {
        id: Date.now(),
        date: document.getElementById('date').value,
        type: document.getElementById('type').value,
        amount: amountVal,
        category: document.getElementById('category').value,
        source: document.getElementById('source').value,
        person: document.getElementById('person').value,
        notes: document.getElementById('notes').value
    };

    transactions.push(newTransaction);
    form.reset();
    loadTransactions();
});

// Delete Transaction
function deleteTransaction(id) {
    if (confirm('Are you sure you want to delete this record?')) {
        transactions = transactions.filter(t => t.id !== id);
        loadTransactions();
    }
}

// Edit Functionality
function editTransaction(id) {
    const trans = transactions.find(t => t.id === id);
    if (trans) {
        // Pre-fill form
        document.getElementById('date').value = trans.date;
        document.getElementById('type').value = trans.type;
        document.getElementById('amount').value = trans.amount;
        document.getElementById('category').value = trans.category;
        document.getElementById('source').value = trans.source;
        document.getElementById('person').value = trans.person;
        document.getElementById('notes').value = trans.notes;

        // Remove old entry to be replaced on save
        transactions = transactions.filter(t => t.id !== id);
        loadTransactions();

        // Scroll to form for better UX on mobile
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }
}
