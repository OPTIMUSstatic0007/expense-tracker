# Validation Checklist

## Complete Test Checklist
### Core CRUD Validation
- [ ] Ensure a new transaction creates correctly and resets total balances.
- [ ] Ensure editing updates the amount/category and recalculates `balanceAfter` across all records sequentially.
- [ ] Ensure soft/hard deletion recalculates `balanceAfter` correctly.

### Ordering Validation
- [ ] Verify entries on the same day maintain strict creation order.

### Balance Validation
- [ ] Verify "Global Balance" equals the final calculated row.

### Backup & Restore Validation
- [ ] Verify `auto` backups generate.
- [ ] Verify manual database restore accurately drops connection, swaps file, and reconnects.
- [ ] Verify Emergency Backup generates before any overwrite.

### Sync Validation
- [ ] Native App Google Sign-In intent resolves correctly.
- [ ] Firebase interactions execute (pending wiring).
