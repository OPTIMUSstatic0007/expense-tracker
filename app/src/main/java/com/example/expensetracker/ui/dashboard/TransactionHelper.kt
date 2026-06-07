package com.example.expensetracker.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransactionHelper {

    fun formatCurrency(amount: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN")) // using IN as per backend standard '₹'
        format.maximumFractionDigits = 2
        format.minimumFractionDigits = 0
        return format.format(amount)
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getCategoryIcon(category: String): ImageVector {
        return when (category) {
            "Groceries" -> Icons.Default.ShoppingCart
            "Electricity" -> Icons.Default.Receipt
            "Water" -> Icons.Default.WaterDrop
            "Gas" -> Icons.Default.LocalGasStation
            "Maintenance" -> Icons.Default.Build
            "Salary" -> Icons.Default.LocalAtm
            "Deposit" -> Icons.Default.AccountBalance
            "Medical" -> Icons.Default.LocalHospital
            "Rent" -> Icons.Default.Home
            "Transportation" -> Icons.Default.DirectionsBus
            "Food" -> Icons.Default.Fastfood
            else -> Icons.Default.Receipt
        }
    }
}
