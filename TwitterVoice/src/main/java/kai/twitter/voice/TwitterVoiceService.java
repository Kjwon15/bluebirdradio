package kai.twitter.voice;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import kai.twitter.voice.manageAccount.ManageAccountsActivity;
import kai.twitter.voice.tweetFilter.StatusManager;
import kai.twitter.voice.util.CustomToast;
import twitter4j.DirectMessage;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.TwitterStream;
import twitter4j.TwitterStreamFactory;
import twitter4j.User;
import twitter4j.UserList;
import twitter4j.UserStreamListener;
import twitter4j.auth.AccessToken;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

/**
 * Created by kjwon15 on 2014. 3. 30..
 */
public class TwitterVoiceService extends Service implements OnInitListener {
    private static final String SHOW = "Show";
    private static final String STOP = "Stop";
    private StatusManager statusManager;
    private DbAdapter adapter;
    private TextToSpeech tts;
    private List<TwitterStream> streams;
    private List<Long> myIds;
    private SharedPreferences preferences;
    private HeadphoneReceiver headphoneReceiver;
    private boolean headphoneReceiverOn = false;
    private PrefChangeListener prefChangeListener;
    private boolean opt_speak_screenname;
    private boolean opt_stop_on_unplugged;
    private boolean opt_remove_url;
    private boolean opt_merge_continuous;
    private int opt_mute_time;

    public static boolean isRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (TwitterVoiceService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SHOW)) {
                    showMain();
                } else if (action.equals(STOP)) {
                    this.stopSelf();
                }
            }
        }

        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        adapter = new DbAdapter(this);
        tts = new TextToSpeech(this, this);
        streams = new ArrayList<>();
        myIds = new ArrayList<>();
        headphoneReceiver = new HeadphoneReceiver();
        initConfig();
        statusManager = new StatusManager(opt_mute_time);
        makeNotification();
        loginTwitter();
        broadcastService(true);
    }

    private void broadcastService(boolean started) {
        Intent intent = new Intent();
        intent.setAction(getResources().getString(R.string.ACTION_SERVICE_TOGGLE));
        intent.putExtra("STARTED", started);

        sendBroadcast(intent);
    }

    private void initConfig() {
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        readConfig();

        prefChangeListener = new PrefChangeListener();
        IntentFilter filter = new IntentFilter();
        filter.addAction(getResources().getString(R.string.ACTION_CHANGE_PREFERENCE));
        registerReceiver(prefChangeListener, filter);
    }

    private void readConfig() {
        opt_speak_screenname = preferences.getBoolean("speak_screenname", false);
        opt_stop_on_unplugged = preferences.getBoolean("stop_on_unplugged", true);
        opt_remove_url = preferences.getBoolean("remove_url", true);
        try {
            opt_mute_time = Integer.parseInt(preferences.getString("mute_time", "60"));
        } catch (NumberFormatException e) {
            opt_mute_time = 0;
        }
        opt_merge_continuous = preferences.getBoolean("merge_continuous", true);

        registerHeadsetReceiver();
    }

    private void makeNotification() {
        Intent mainIntent = new Intent(this, this.getClass());
        Intent stopIntent = new Intent(this, this.getClass());
        mainIntent.setAction(SHOW);
        stopIntent.setAction(STOP);
        PendingIntent pMainIntent = PendingIntent.getService(this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(this)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.noti_running)))
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.noti_running))
                .setSmallIcon(R.drawable.ic_stat_notify_service)
                .setContentIntent(pMainIntent)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.noti_action_stop), pStopIntent)
                .build();

        startForeground(1, notification);
    }

    private void registerHeadsetReceiver() {
        if (opt_stop_on_unplugged) {
            if (!headphoneReceiverOn) {
                headphoneReceiverOn = true;
                headphoneReceiver.reset();
                registerReceiver(headphoneReceiver,
                        new IntentFilter(Intent.ACTION_HEADSET_PLUG));
                registerReceiver(headphoneReceiver,
                        new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
            }
        } else {
            if (headphoneReceiverOn) {
                headphoneReceiverOn = false;
                unregisterReceiver(headphoneReceiver);
            }
        }
    }

    private void loginTwitter() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<AccessToken> tokens = adapter.getAccounts();
                if (tokens.isEmpty()) {
                    showManageAccounts();
                    stopSelf();
                    return;
                }

                for (AccessToken token : tokens) {
                    Configuration conf = new ConfigurationBuilder()
                            .setOAuthConsumerKey(getString(R.string.CONSUMER_KEY))
                            .setOAuthConsumerSecret(getString(R.string.CONSUMER_SECRET))
                            .setOAuthAccessToken(token.getToken())
                            .setOAuthAccessTokenSecret(token.getTokenSecret())
                            .build();

                    try {
                        long id = new TwitterFactory(conf).getInstance().getId();
                        myIds.add(id);
                    } catch (TwitterException e) {
                        Log.e("TweetError", "Failed to add id to myIds");
                        Log.e("TweetError", e.getMessage());
                    }

                    TwitterStream stream = new TwitterStreamFactory(conf).getInstance();
                    streams.add(stream);
                    stream.addListener(new Listener());
                    stream.user();
                }

            }
        }).start();
    }

    @Override
    public void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        if (streams != null) {
            for (TwitterStream stream : streams) {
                stream.clearListeners();
                stream.cleanUp();
                stream.shutdown();
            }
            streams.clear();
        }

        stopForeground(true);

        if (headphoneReceiverOn) {
            headphoneReceiverOn = false;
            unregisterReceiver(headphoneReceiver);
        }

        unregisterReceiver(prefChangeListener);

        CustomToast.makeText(this, getString(R.string.service_stopped), Toast.LENGTH_SHORT).show();

        broadcastService(false);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            CustomToast.makeText(this, getString(R.string.service_started), Toast.LENGTH_SHORT).show();
        } else {
            Log.e("TTS", "Initialize failed");
            CustomToast.makeText(this, getString(R.string.initialize_failed), Toast.LENGTH_SHORT).show();
            this.stopSelf();
        }
    }

    private void showMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void showManageAccounts() {
        Intent intent = new Intent(this, ManageAccountsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void speak(String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts21(message);
        } else {
            ttsOld(message);
        }
    }

    @SuppressWarnings("deprecation")
    private void ttsOld(String message) {
        HashMap<String, String> map = new HashMap<>();
        map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageId");
        tts.speak(message, TextToSpeech.QUEUE_ADD, map);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void tts21(String message) {
        String utteranceId = String.valueOf(this.hashCode());
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    private class PrefChangeListener extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String name = intent.getAction();
            if (name.equals(getString(R.string.ACTION_CHANGE_PREFERENCE))) {
                readConfig();
            }
        }
    }

    private class Listener implements UserStreamListener {

        private String removeUrl(String message) {
            String urlPattern = "(https?://\\S+)(\\s|$)";
            return message.replaceAll(urlPattern, "");
        }

        private String mergeContinuous(String message) {
            String continuousPattern = "(.)\\1{2,}";
            return message.replaceAll(continuousPattern, "$1$1");
        }

        private String getUserName(User user) {
            return opt_speak_screenname ? user.getScreenName() : user.getName();
        }

        private String formatMessage(Status status) {
            User user = status.getUser();
            String text = status.getText();

            if (opt_remove_url) {
                text = removeUrl(text);
            }

            if (opt_merge_continuous) {
                text = mergeContinuous(text);
            }

            return MessageFormat.format("{0}: {1}",
                    getUserName(user),
                    text);
        }

        @Override
        public void onStatus(Status status) {

            if (statusManager.isSpoken(status)) {
                Log.d("Tweet", "Duplicated");
                return;
            }

            String message;

            if (status.isRetweet()) {
                Status retweetedStatus = status.getRetweetedStatus();
                message = MessageFormat.format(
                        getString(R.string.retweeted_by),
                        getUserName(status.getUser()),
                        formatMessage(retweetedStatus));
            } else {
                message = formatMessage(status);
            }

            speak(message);
            Log.d("Tweet", message);
        }

        @Override
        public void onDirectMessage(DirectMessage directMessage) {
            User sender = directMessage.getSender();

            if (myIds.contains(sender.getId())) {
                return;
            }

            String text = directMessage.getText();
            String message = MessageFormat.format(getString(R.string.dm_from),
                    opt_speak_screenname ? sender.getScreenName() : sender.getName(),
                    text);

            if (opt_remove_url) {
                message = removeUrl(message);
            }

            if (opt_merge_continuous) {
                message = mergeContinuous(message);
            }

            speak(message);
            Log.d("DM", message);
        }

        @Override
        public void onFollow(User user, User user2) {
            if (myIds.contains(user2.getId())) {
                String message = MessageFormat.format(getString(R.string.followed_by),
                        opt_speak_screenname ? user.getScreenName() : user.getName(),
                        opt_speak_screenname ? user2.getScreenName() : user2.getName());
                speak(message);
            }
        }

        @Override
        public void onDeletionNotice(long l, long l2) {

        }

        @Override
        public void onFriendList(long[] longs) {

        }

        @Override
        public void onFavorite(User user, User user2, Status status) {

        }

        @Override
        public void onUnfavorite(User user, User user2, Status status) {

        }

        @Override
        public void onUnfollow(User user, User user2) {

        }

        @Override
        public void onUserListMemberAddition(User user, User user2, UserList userList) {

        }

        @Override
        public void onUserListMemberDeletion(User user, User user2, UserList userList) {

        }

        @Override
        public void onUserListSubscription(User user, User user2, UserList userList) {

        }

        @Override
        public void onUserListUnsubscription(User user, User user2, UserList userList) {

        }

        @Override
        public void onUserListCreation(User user, UserList userList) {

        }

        @Override
        public void onUserListUpdate(User user, UserList userList) {

        }

        @Override
        public void onUserListDeletion(User user, UserList userList) {

        }

        @Override
        public void onUserProfileUpdate(User user) {

        }

        @Override
        public void onUserSuspension(long l) {

        }

        @Override
        public void onUserDeletion(long l) {

        }

        @Override
        public void onBlock(User user, User user2) {

        }

        @Override
        public void onUnblock(User user, User user2) {

        }

        @Override
        public void onRetweetedRetweet(User source, User target, Status retweetedStatus) {

        }

        @Override
        public void onFavoritedRetweet(User user, User user1, Status status) {

        }

        @Override
        public void onQuotedTweet(User source, User target, Status quotingStatus) {
            if (statusManager.isSpoken(quotingStatus)) {
                Log.d("Tweet", "Duplicated");
                return;
            }

            Status quotedStatus = quotingStatus.getQuotedStatus();

            String message = MessageFormat.format(
                    getString(R.string.quoted_by),
                    formatMessage(quotingStatus),
                    formatMessage(quotedStatus)
            );
            speak(message);
            Log.d("Quoted tweet", message);
        }

        @Override
        public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {

        }

        @Override
        public void onTrackLimitationNotice(int i) {
            Log.w("TweetLimit", Integer.toString(i));
        }

        @Override
        public void onScrubGeo(long l, long l2) {

        }

        @Override
        public void onStallWarning(StallWarning stallWarning) {
            Log.w("TweetWarning", stallWarning.getMessage());
        }

        @Override
        public void onException(Exception e) {
            String message = e.getMessage();
            if (message != null) {
                Log.e("TweetException", e.getMessage());
            }
        }
    }
}
