package com.cloudreel.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClassicCard
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.cloudreel.tv.ui.theme.YingShiColors

@Composable
fun PosterCard(
    title: String,
    poster: String?,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClassicCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().touchActivate(onClick = onClick),
        image = {
            PosterFace(
                title = title,
                poster = poster,
                modifier = Modifier.fillMaxWidth().height(248.dp),
            )
        },
        title = {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
        },
        subtitle = {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = YingShiColors.Sub)
        },
    )
}

@Composable
fun PosterFace(title: String, poster: String?, modifier: Modifier = Modifier) {
    if (poster.isNullOrBlank()) {
        Box(
            modifier = modifier.background(
                Brush.linearGradient(listOf(Color(0xFF3C5670), Color(0xFF151A22), Color(0xFF0E1218))),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(2).ifBlank { "片" }, color = YingShiColors.Text, fontSize = 28.sp)
        }
    } else {
        AsyncImage(
            model = poster,
            contentDescription = title,
            modifier = modifier.background(YingShiColors.Card2),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(top = 8.dp, bottom = 10.dp),
        fontSize = 20.sp,
    )
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = YingShiColors.Text, fontSize = 18.sp),
        cursorBrush = SolidColor(YingShiColors.Primary),
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch == null) ImeAction.Done else ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }, onDone = { onSearch?.invoke() }),
        modifier = modifier
            .background(YingShiColors.Card2, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = YingShiColors.Sub, fontSize = 18.sp)
                inner()
            }
        },
    )
}
