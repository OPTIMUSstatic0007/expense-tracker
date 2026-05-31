// API Configuration
const API_BASE_URL = 'http://localhost:8080/transactions';

// State management
let transactions = [];

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

// Fetch all transactions from Backend
async function loadTransactions() {
    try {
        const response = await fetch(API_BASE_URL);
        if (!response.ok) throw new Error('Failed to fetch transactions');
        transactions = await response.json();
        renderTable();
        updateSummary();
    } catch (error) {
        console.error('Error:', error);
        alert('Could not connect to backend. Ensure Ktor server is running on port 8080.');
    }
}

// Render Ledger Table
function renderTable() {
    historyBody.innerHTML = '';

    // Transactions are already sorted and balanced by the backend
    transactions.forEach(t => {
        const isCredit = t.entryType === 'Credit';
        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${t.date}</td>
            <td>
                <div style="font-weight: 500;">${t.category}</div>
                <div style="font-size: 0.75rem; color: var(--text-muted);">${t.paidTo || ''}</div>
            </td>
            <td>${t.expenseType}</td>
            <td><span class="stat-label" style="font-size: 0.7rem; color: ${isCredit ? 'var(--success)' : 'var(--danger)'}">${isCredit ? 'ADDED' : 'EXPENSE'}</span></td>
            <td class="text-right">${!isCredit ? t.amount.toFixed(2) : '-'}</td>
            <td class="text-right">${isCredit ? t.amount.toFixed(2) : '-'}</td>
            <td class="text-right" style="font-weight: 600;">${t.balanceAfter.toFixed(2)}</td>
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
        .filter(t => t.entryType === 'Credit')
        .reduce((sum, t) => sum + t.amount, 0);

    const totalDebit = transactions
        .filter(t => t.entryType === 'Debit')
        .reduce((sum, t) => sum + t.amount, 0);

    const balance = totalCredit - totalDebit;

    balanceEl.innerText = currencyFormatter.format(balance);
    creditEl.innerText = currencyFormatter.format(totalCredit);
    debitEl.innerText = currencyFormatter.format(totalDebit);
}

// Handle Form Submit (Add/Update)
let editingId = null;

form.addEventListener('submit', async (e) => {
    e.preventDefault();

    const amountVal = parseFloat(document.getElementById('amount').value);
    if (isNaN(amountVal)) return;

    const transactionData = {
        date: document.getElementById('date').value,
        entryType: document.getElementById('type').value,
        amount: amountVal,
        category: document.getElementById('category').value,
        expenseType: document.getElementById('source').value,
        paidTo: document.getElementById('person').value,
        notes: document.getElementById('notes').value
    };

    try {
        let response;
        if (editingId) {
            // Update existing
            response = await fetch(`${API_BASE_URL}/${editingId}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(transactionData)
            });
            editingId = null;
            document.getElementById('save-btn').innerText = 'Save to Ledger';
        } else {
            // Create new
            response = await fetch(API_BASE_URL, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(transactionData)
            });
        }

        if (response.ok) {
            form.reset();
            loadTransactions();
        } else {
            alert('Failed to save transaction');
        }
    } catch (error) {
        console.error('Error saving:', error);
    }
});

// Delete Transaction
async function deleteTransaction(id) {
    if (confirm('Are you sure you want to delete this record?')) {
        try {
            const response = await fetch(`${API_BASE_URL}/${id}`, {
                method: 'DELETE'
            });
            if (response.ok) {
                loadTransactions();
            } else {
                alert('Failed to delete');
            }
        } catch (error) {
            console.error('Error deleting:', error);
        }
    }
}

// Edit Transaction
function editTransaction(id) {
    const trans = transactions.find(t => t.id === id);
    if (trans) {
        document.getElementById('date').value = trans.date;
        document.getElementById('type').value = trans.entryType;
        document.getElementById('amount').value = trans.amount;
        document.getElementById('category').value = trans.category;
        document.getElementById('source').value = trans.expenseType;
        document.getElementById('person').value = trans.paidTo;
        document.getElementById('notes').value = trans.notes;

        editingId = id;
        document.getElementById('save-btn').innerText = 'Update Entry';
        window.scrollTo({ top: 0, behavior: 'smooth' });
    }
}
