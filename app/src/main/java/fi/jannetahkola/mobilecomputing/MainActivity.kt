package fi.jannetahkola.mobilecomputing

import SampleData
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import fi.jannetahkola.mobilecomputing.data.AppDatabase
import fi.jannetahkola.mobilecomputing.data.User
import fi.jannetahkola.mobilecomputing.ui.theme.MobileComputingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var tempSensor: Sensor? = null
    private var tempValue: Float? = null
    private var tempValueLastNotified: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initPermissions()
        createNotificationChannel()
        initAmbientTemperatureSensor()

        setContent {
            val users = AppDatabase.getDatabase(applicationContext)
                .userDao().getAll().collectAsState(initial = listOf())
            Log.i("Users", "users: $users")
            MobileComputingTheme {
                MyNavHost(
                    applicationContext = applicationContext,
                    users = users
                )
            }
        }
    }

    companion object {
        const val LOG_SENSOR = "sensor";
        const val LOG_NOTIFICATION = "notification"

        const val NOTIFICATION_CHANNEL_ID: String = "test-channel-id"
        const val NOTIFICATION_ID: Int = 1
        const val NOTIFICATION_PERMISSION_AGREE_CODE = 1

        const val SENSOR_NOTIFICATION_INTERVAL_DEGREES = 2;
    }

    private fun createNotificationChannel() {
        val name = "test-channel"
        val descriptionText = "test channel description"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            .apply {
                description = descriptionText
            }
        // Register the channel with the system.
        val notificationManager: NotificationManager =
            getSystemService(this, NotificationManager::class.java) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun initPermissions() {
        Log.i(LOG_NOTIFICATION, "Requesting permissions...")
        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.POST_NOTIFICATIONS // Note the 'android' qualifier here
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_AGREE_CODE
                )

                return@with
            }
        }
    }

    private fun initAmbientTemperatureSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        tempSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        if (tempSensor != null) {
            Log.i(LOG_SENSOR, "Ambient temp sensor found!")
        } else {
            Log.i(LOG_SENSOR, "Ambient temp sensor NOT found!")
        }
    }

    private fun sendNotification(title: String, contentText: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
//            .setStyle(NotificationCompat.BigTextStyle()
//                .bigText("Much longer text that cannot fit one line..."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Remove notification on tap

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.POST_NOTIFICATIONS // Note the 'android' qualifier here
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(LOG_NOTIFICATION, "Failed to send notification - permissions not granted")

                // No point requesting at this point anymore
//                ActivityCompat.requestPermissions(
//                    this@MainActivity,
//                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
//                    NOTIFICATION_PERMISSION_AGREE_CODE
//                )

                return@with
            }
            // TODO Should the id be generated for each call?
            notify(NOTIFICATION_ID, builder.build())
            Log.i(LOG_NOTIFICATION, "Notification sent")
        }
    }

    // Idk why the last two don't work
    @Suppress("MissingSuperCall", "OverridingDeprecatedMember", "Deprecation")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            NOTIFICATION_PERMISSION_AGREE_CODE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    Log.i(LOG_NOTIFICATION,"Permissions granted")
                else
                    Log.i(LOG_NOTIFICATION,"Permissions not granted")
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            val oldTempValue = tempValue
            val newTempValue = event.values[0]
            val lastNotifiedWithValue = tempValueLastNotified

            val doNotify =
                // a value has been set once - do not notify on app start AND
                (oldTempValue != null)
                        // never notified before OR
                    && ((lastNotifiedWithValue == null)
                        // diff from last notified value is >= interval
                    || (abs(lastNotifiedWithValue.minus(newTempValue)) >= SENSOR_NOTIFICATION_INTERVAL_DEGREES))

            tempValue = newTempValue

            Log.i(LOG_SENSOR, "Received value: $tempValue")

            if (doNotify) {
                tempValueLastNotified = tempValue
                sendNotification("Temperature changed", "Current ambient temperature is $tempValue C")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.i(LOG_SENSOR, "Sensor accuracy changed to: $accuracy")
    }

    override fun onResume() {
        super.onResume()
        Log.i(LOG_SENSOR, "Resuming sensor listener")
        tempSensor?.also { temp ->
            sensorManager.registerListener(this, temp, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        // Do not pause so that we can trigger it when not on foreground
        Log.i(LOG_SENSOR, "NOT pausing sensor listener")
//        Log.i(LOG_SENSOR, "Pausing sensor listener")
//        sensorManager.unregisterListener(this)
    }
}

@Composable
fun MyNavHost(modifier: Modifier = Modifier,
              navController: NavHostController = rememberNavController(),
              startDestination: String = "conversation",
              applicationContext: Context,
              users: State<List<User>>
) {
    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = startDestination
    ) {
        composable("conversation") {
            Conversation(
                messages = SampleData.conversationSample,
                users = users,
                onNavigateToProfile = {
                    navController.navigate("profile")
                }
            )
        }
        composable("profile") {
            Profile(
                applicationContext = applicationContext,
                users = users,
                onNavigateToConversation = {
                    navController.navigate("conversation")  {
                        popUpTo("conversation") {
                            inclusive = true
                        }
                    }
                }
            )
        }
    }
}

data class Message(val author: String, val body: String)

@Preview(name = "Light Mode")
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    name = "Dark Mode"
)
@Composable
fun MessageCard(msg: Message) {
    Row(modifier = Modifier.padding(all = 8.dp)) {
        Image(
            painter = painterResource(id = R.drawable.profile_icon),
            contentDescription = "Contact profile picture",
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
        )

        Spacer(modifier = Modifier.width(8.dp))

        var isExpanded by remember { mutableStateOf(false) }
        val surfaceColor by animateColorAsState(
            if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        )

        Column(modifier = Modifier.clickable { isExpanded = !isExpanded }) {
            Text(
                text = msg.author,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 1.dp,
                color = surfaceColor,
                modifier = Modifier
                    .animateContentSize()
                    .padding(1.dp)
            ) {
                Text(
                    text = msg.body,
                    modifier = Modifier.padding(all = 4.dp),
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun Conversation(messages: List<Message>,
                 users: State<List<User>>,
                 onNavigateToProfile: () -> Unit) {
    val userImage = if (users.value.isNotEmpty() && users.value[0].userImage != null) users.value[0].userImage else null
    val username = if (users.value.isNotEmpty() && users.value[0].username != null) users.value[0].username else null
    LazyColumn {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(all = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Conversation", style = MaterialTheme.typography.titleLarge)
                Row (verticalAlignment = Alignment.CenterVertically) {
                    ProfilePicture(imageUri = userImage)
                    Button(
                        onClick = onNavigateToProfile,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(text = if (username != null) "$username's Profile" else "Profile")
                    }
                }
            }
        }
        items(messages) { message ->
            MessageCard(message)
        }
    }
}

@Composable
fun Profile(applicationContext: Context, users: State<List<User>>, onNavigateToConversation: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    val usernameTextMaxChars = 20
    val (usernameText, setUsernameText) = remember { mutableStateOf<String?>(null) }
    val userImage = if (users.value.isNotEmpty() && users.value[0].userImage != null) users.value[0].userImage else null
    val username = if (users.value.isNotEmpty() && users.value[0].username != null) users.value[0].username else null

    // Registers a photo picker activity launcher in single-select mode.
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Callback is invoked after the user selects a media item or closes the
        // photo picker.
        if (uri != null) {
            Log.d("PhotoPicker", "Selected URI: $uri")

            // Keep access to selected image between restarts
            val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
            applicationContext.contentResolver.takePersistableUriPermission(uri, flag)

            // Store image URI
            coroutineScope.launch(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext)
                    .userDao().upsert(User(1, usernameText, uri.toString()))
            }
        } else {
            Log.d("PhotoPicker", "No media selected")
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (username != null) "$username's Profile" else "Profile",
                style = MaterialTheme.typography.titleLarge
            )
            Button(onClick = onNavigateToConversation) {
                Text(text = "Back")
            }
        }
        Row (
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(imageUri = userImage)
            Button(
                onClick = {
                    // Launch the photo picker and let the user choose only images.
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                Text(text = "Pick an image")
            }
        }
        Row {
            TextField(
                value = usernameText ?: "",
                onValueChange = { if (it.length <= usernameTextMaxChars) setUsernameText(it.trim()) },
                label = { Text("New username") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        text = "${usernameText?.length ?: 0}/$usernameTextMaxChars",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            )
        }
        Row {
            Button(
                enabled = usernameText?.isNotBlank() ?: false,
                onClick = {
                    if (usernameText != null) {
                        coroutineScope.launch(Dispatchers.IO) {
                            AppDatabase.getDatabase(applicationContext)
                                .userDao().upsert(User(1, usernameText, userImage))
                            setUsernameText(null)
                        }
                    }
                }
            ) {
                Text(text = "Save username")
            }
        }
    }
}

@Composable
fun ProfilePicture(imageUri: String?) {
    val imageDescription = "Profile picture"
    val imageModifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)

    Log.i("ProfilePicture", "Loading image from Uri=$imageUri")

    if (imageUri != null)
        Image(
            painter = rememberAsyncImagePainter(model = Uri.parse(imageUri)),
            contentDescription = imageDescription,
            modifier = imageModifier
        )
    else
        Image(
            painter = painterResource(id = R.drawable.profile_icon),
            contentDescription = imageDescription,
            modifier = imageModifier
        )
}