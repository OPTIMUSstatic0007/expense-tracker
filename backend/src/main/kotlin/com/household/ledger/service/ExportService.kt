package com.household.ledger.service

import com.household.ledger.models.Transaction
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ExportService {
    fun generateExcel(transactions: List<Transaction>, month: String?, year: String?): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Ledger")

        // Styles
        val headerFont = workbook.createFont().apply {
            bold = true
            color = IndexedColors.WHITE.index
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(headerFont)
            fillForegroundColor = IndexedColors.CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val summaryFont = workbook.createFont().apply { bold = true }
        val summaryStyle = workbook.createCellStyle().apply { setFont(summaryFont) }

        // Summary Section
        var rowIdx = 0
        val exportDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        sheet.createRow(rowIdx++).createCell(0).apply {
            setCellValue("Household Ledger Export")
            cellStyle = summaryStyle
        }
        sheet.createRow(rowIdx++).createCell(0).setCellValue("Export Date: $exportDate")
        
        val monthStr = if (month.isNullOrEmpty()) "All Months" else "Month: $month"
        val yearStr = if (year.isNullOrEmpty()) "All Years" else "Year: $year"
        sheet.createRow(rowIdx++).createCell(0).setCellValue("Period: $monthStr / $yearStr")
        rowIdx++ // Gap

        val totalCredit = transactions.filter { it.entryType == "Credit" }.sumOf { it.amount }
        val totalDebit = transactions.filter { it.entryType == "Debit" }.sumOf { it.amount }
        val balance = if (transactions.isNotEmpty()) transactions.last().balanceAfter ?: BigDecimal.ZERO else BigDecimal.ZERO

        sheet.createRow(rowIdx++).apply {
            createCell(0).setCellValue("Total Cash Added:")
            createCell(1).setCellValue(totalCredit.toDouble())
        }
        sheet.createRow(rowIdx++).apply {
            createCell(0).setCellValue("Total Expenses:")
            createCell(1).setCellValue(totalDebit.toDouble())
        }
        sheet.createRow(rowIdx++).apply {
            createCell(0).setCellValue("Remaining Balance:")
            createCell(1).setCellValue(balance.toDouble())
        }
        rowIdx++ // Gap

        // Table Header
        val headers = arrayOf("Date", "Type", "Category", "Payment Mode", "Paid To", "Notes", "Debit (Expense)", "Credit (Added)", "Balance")
        val headerRow = sheet.createRow(rowIdx++)
        headers.forEachIndexed { i, h ->
            val cell = headerRow.createCell(i)
            cell.setCellValue(h)
            cell.cellStyle = headerStyle
        }

        // Data Rows
        transactions.forEach { t ->
            val row = sheet.createRow(rowIdx++)
            row.createCell(0).setCellValue(t.date)
            row.createCell(1).setCellValue(t.entryType)
            row.createCell(2).setCellValue(t.category)
            row.createCell(3).setCellValue(t.expenseType)
            row.createCell(4).setCellValue(t.paidTo)
            row.createCell(5).setCellValue(t.notes)
            
            if (t.entryType == "Debit") {
                row.createCell(6).setCellValue(t.amount.toDouble())
                row.createCell(7).setCellValue(0.0)
            } else {
                row.createCell(6).setCellValue(0.0)
                row.createCell(7).setCellValue(t.amount.toDouble())
            }
            row.createCell(8).setCellValue(t.balanceAfter?.toDouble() ?: 0.0)
        }

        // Auto-size columns
        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
        }

        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()
        return out.toByteArray()
    }

    fun generateCsv(transactions: List<Transaction>, month: String?, year: String?): String {
        val sb = StringBuilder()
        val exportDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        
        sb.append("Household Ledger Export\n")
        sb.append("Export Date, $exportDate\n")
        val monthStr = if (month.isNullOrEmpty()) "All" else month
        val yearStr = if (year.isNullOrEmpty()) "All" else year
        sb.append("Period, $monthStr / $yearStr\n\n")

        val totalCredit = transactions.filter { it.entryType == "Credit" }.sumOf { it.amount }
        val totalDebit = transactions.filter { it.entryType == "Debit" }.sumOf { it.amount }
        val balance = if (transactions.isNotEmpty()) transactions.last().balanceAfter ?: BigDecimal.ZERO else BigDecimal.ZERO

        sb.append("Total Cash Added, ${totalCredit.toPlainString()}\n")
        sb.append("Total Expenses, ${totalDebit.toPlainString()}\n")
        sb.append("Remaining Balance, ${balance.toPlainString()}\n\n")

        sb.append("Date,Entry Type,Category,Payment Mode,Paid To,Notes,Debit,Credit,Balance\n")
        
        transactions.forEach { t ->
            val debit = if (t.entryType == "Debit") t.amount.toPlainString() else "0.00"
            val credit = if (t.entryType == "Credit") t.amount.toPlainString() else "0.00"
            val balanceAfter = t.balanceAfter?.toPlainString() ?: "0.00"
            
            sb.append("\"${t.date}\",")
            sb.append("\"${t.entryType}\",")
            sb.append("\"${t.category}\",")
            sb.append("\"${t.expenseType}\",")
            sb.append("\"${t.paidTo}\",")
            sb.append("\"${t.notes.replace("\"", "\"\"")}\",")
            sb.append("$debit,")
            sb.append("$credit,")
            sb.append("$balanceAfter\n")
        }
        
        return sb.toString()
    }
}
