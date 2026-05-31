const API_BASE_URL = 'http://127.0.0.1:8080/transactions';

let transactions = [];
let editingId = null;

const form = document.getElementById('exp-form');
const historyBody = document.getElementById('history-body');
const balanceEl = document.getElementById('balance');
const creditEl = document.getElementById('total-credit');
const debitEl = document.getElementById('total-debit');
const saveBtn = document.getElementById('save-btn');

document.addEventListener('DOMContentLoaded', () => {
    loadTransactions();
});

// Indian Currency Formatter (₹) with exact 2 decimal places
const currencyFormatter = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
});

async function loadTransactions() {
    try {
        const response = await fetch(API_BASE_URL);
        if (!response.ok) throw new Error('Failed to fetch');
        transactions = await response.json();
        renderTable();
        updateSummary();
    } catch (error) {
        console.error('Error:', error);
        alert('Backend unreachable. Ensure Ktor is running on port 8080.');
    }
}

function renderTable() {
    historyBody.innerHTML = '';
    transactions.forEach(t => {
        const isCredit = t.entryType === 'Credit';
        // Parse strings from BigDecimalSerializer to numbers for display formatting
        const amount = parseFloat(t.amount);
        const balanceAfter = parseFloat(t.balanceAfter);

        const row = document.createElement('tr');
        row.innerHTML = `
            <td>${t.date}</td>
            <td>
                <div style="font-weight: 500;">${t.category}</div>
                <div style="font-size: 0.75rem; color: #666;">${t.paidTo || '-'}</div>
            </td>
            <td>${t.expenseType}</td>
            <td><span class="stat-label" style="font-size: 0.7rem; color: ${isCredit ? '#2e7d32' : '#d32f2f'}">${isCredit ? 'ADDED' : 'EXPENSE'}</span></td>
            <td class="text-right">${!isCredit ? amount.toFixed(2) : '-'}</td>
            <td class="text-right">${isCredit ? amount.toFixed(2) : '-'}</td>
            <td class="text-right" style="font-weight: 600;">${balanceAfter.toFixed(2)}</td>
            <td class="text-center">
                <button class="btn-action btn-edit" onclick="editTransaction(${t.id})">Edit</button>
                <button class="btn-action btn-delete" onclick="deleteTransaction(${t.id})">Delete</button>
            </td>
        `;
        historyBody.appendChild(row);
    });
}

form.addEventListener('submit', async (e) => {
    e.preventDefault();

    // Send as string to preserve precision on the backend
    const amountInput = document.getElementById('amount').value;

    const data = {
        date: document.getElementById('date').value,
        entryType: document.getElementById('type').value,
        amount: amountInput,
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
            form.reset();
            editingId = null;
            saveBtn.innerText = 'Save to Ledger';
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
    document.getElementById('amount').value = t.amount; // Use string value directly
    document.getElementById('category').value = t.category;
    document.getElementById('source').value = t.expenseType;
    document.getElementById('person').value = t.paidTo;
    document.getElementById('notes').value = t.notes;
    editingId = id;
    saveBtn.innerText = 'Update Entry';
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

function updateSummary() {
    // For summary, we can use numbers as the backend already did the precise math for balanceAfter
    const credit = transactions
        .filter(t => t.entryType === 'Credit')
        .reduce((s, t) => s + parseFloat(t.amount), 0);

    const debit = transactions
        .filter(t => t.entryType === 'Debit')
        .reduce((s, t) => s + parseFloat(t.amount), 0);

    const latestBalance = transactions.length > 0
        ? parseFloat(transactions[transactions.length - 1].balanceAfter)
        : 0;

    balanceEl.innerText = currencyFormatter.format(latestBalance);
    creditEl.innerText = currencyFormatter.format(credit);
    debitEl.innerText = currencyFormatter.format(debit);
}
