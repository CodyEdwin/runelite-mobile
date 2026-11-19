package com.codyedwin.runelitemobile;

import static com.codyedwin.runelitemobile.Tools.shareLog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

@Keep
public class ExitActivity extends AppCompatActivity {

    @SuppressLint("StringFormatInvalid") //invalid on some translations but valid on most, cant fix that atm
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int code = -1;
        boolean isSignal = false;
        Bundle extras = getIntent().getExtras();
        if(extras != null) {
            code = extras.getInt("code",-1);
            isSignal = extras.getBoolean("isSignal", false);
        }

        int message = isSignal ? R.string.mcn_signal_title : R.string.mcn_exit_title;

        // Exit code 3 comes from the Android shim (AndroidRuneLite.EXIT_STARTUP_FAILED) when
        // RuneLite reports "Failure during startup" instead of opening its window.
        String text = (!isSignal && code == 3)
                ? "RuneLite failed to start. Tap \"Share log file\" to send the log. If it keeps happening, "
                  + "pick \"Bundled client only\" under \"RuneLite version & updates\"."
                : getString(message, code);
        new AlertDialog.Builder(this)
                .setMessage(text)
                .setPositiveButton(R.string.main_share_logs, (dialog, which) -> shareLog(this))
                .setOnDismissListener(dialog -> ExitActivity.this.finish())
                .show();
    }

    public static void showExitMessage(Context ctx, int code, boolean isSignal) {
        Intent i = new Intent(ctx,ExitActivity.class);
        i.putExtra("code",code);
        i.putExtra("isSignal", isSignal);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

}
