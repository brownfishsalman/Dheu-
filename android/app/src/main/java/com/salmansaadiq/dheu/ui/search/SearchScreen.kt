package com.salmansaadiq.dheu.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salmansaadiq.dheu.data.Person
import com.salmansaadiq.dheu.data.Profiles
import com.salmansaadiq.dheu.data.Relationships
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.components.ErrorState
import com.salmansaadiq.dheu.ui.components.Loading
import com.salmansaadiq.dheu.ui.components.PeopleList
import com.salmansaadiq.dheu.ui.components.SectionTitle
import com.salmansaadiq.dheu.ui.components.fieldColors
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.delay

@Composable
fun SearchScreen() {
    val me = LocalAppState.current.userId
    val c = Dheu.colors
    var query by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var relationships by remember { mutableStateOf<Relationships?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    // Search as the member types, with a short pause so every keystroke isn't a request.
    LaunchedEffect(query, attempt) {
        delay(if (query.isEmpty()) 0 else 300)
        try {
            if (relationships == null) relationships = Profiles.relationships(me)
            people = Profiles.search(query)
            error = null
        } catch (e: Exception) {
            error = e.friendly()
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search people") },
            leadingIcon = { Icon(Lucide.Search, null, tint = c.inkFaint) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
        )
        SectionTitle(if (query.isBlank()) "Everyone on Dheu" else "Results for “$query”")
        when {
            error != null && people == null -> ErrorState(error!!, onRetry = { attempt++ })
            people == null -> Loading()
            else -> PeopleList(people!!, relationships, if (query.isBlank()) "No members yet." else "No one matches that.")
        }
    }
}
