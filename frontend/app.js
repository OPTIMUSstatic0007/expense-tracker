// State management
let transactions = [
    {
        id: 1,
        date: '2023-10-01',
        type: 'Credit',
        amount: 5000.00,
        category: 'Salary',
        source: 'Bank Transfer',
        person: 'Employer Inc',
        notes: 'Monthly payout'
    },
    {
        id: 2,
        date: '2023-10-05',
        type: 'Debit',
        amount: 45.50,
        category: 'Utilities',
        source: 'Credit Card',
        person: 'Electric Co',
        notes: 'September Bill'
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

// Main Load Function
function loadTransactions() {
    renderTable();
    updateSummary();
}

// Render Ledger Table
function renderTable() {
    historyBody.innerHTML = '';
    let runningBalance = 0;

    // Sort transactions by date (optional but professional)
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
            <td><span class="stat-label" style="font-size: 0.7rem; color: ${isCredit ? 'var(--success)' : 'var(--danger)'}">${t.type.toUpperCase()}</span></td>
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

    balanceEl.innerText = `$${balance.toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;
    creditEl.innerText = `$${totalCredit.toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;
    debitEl.innerText = `$${totalDebit.toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;
}

// Add New Transaction
form.addEventListener('submit', (e) => {
    e.preventDefault();

    const newTransaction = {
        id: Date.now(),
        date: document.getElementById('date').value,
        type: document.getElementById('type').value,
        amount: parseFloat(document.getElementById('amount').value),
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

// Edit Placeholder
function editTransaction(id) {
    console.log('Edit transaction:', id);
    alert('Edit functionality will be implemented in the next step. ID: ' + id);
}
