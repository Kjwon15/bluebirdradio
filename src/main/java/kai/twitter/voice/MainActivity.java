package kai.twitter.voice;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.Switch;

public class MainActivity extends ActionBarActivity implements CompoundButton.OnCheckedChangeListener {
    private Switch startServiceToggle;
    private SharedPreferences preferences;
    private HeadphoneReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startServiceToggle = (Switch) findViewById(R.id.switch_start_service);
        startServiceToggle.setOnCheckedChangeListener(this);

        receiver = new HeadphoneReceiver();
        preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (preferences.getBoolean("stop_on_unplugged", true)) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
            registerReceiver(receiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startServiceToggle.setChecked(TwitterVoiceService.isRunning());
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        if (R.id.action_settings == item.getItemId()) {
            Intent settings = new Intent(getApplicationContext(), SettingsActivity.class);
            startActivity(settings);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
        if (startServiceToggle.getId() == compoundButton.getId()) {
            Intent intent = new Intent(getApplicationContext(), TwitterVoiceService.class);
            if (checked) {
                startService(intent);
            } else {
                stopService(intent);
            }
        }
    }
}
