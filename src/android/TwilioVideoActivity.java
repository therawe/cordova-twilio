package org.apache.cordova.twiliovideo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.twilio.video.CameraCapturer;
import com.twilio.video.CameraCapturer.CameraSource;
import com.twilio.video.ConnectOptions;
import com.twilio.video.LocalAudioTrack;
import com.twilio.video.LocalParticipant;
import com.twilio.video.LocalVideoTrack;
import com.twilio.video.RemoteAudioTrack;
import com.twilio.video.RemoteAudioTrackPublication;
import com.twilio.video.RemoteDataTrack;
import com.twilio.video.RemoteDataTrackPublication;
import com.twilio.video.RemoteParticipant;
import com.twilio.video.RemoteVideoTrack;
import com.twilio.video.RemoteVideoTrackPublication;
import com.twilio.video.Room;
import com.twilio.video.TwilioException;
import com.twilio.video.Video;
import com.twilio.video.VideoRenderer;
import com.twilio.video.VideoTrack;
import com.twilio.video.VideoView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import capacitor.android.plugins.R;

public class TwilioVideoActivity extends AppCompatActivity implements CallActionObserver {

    /*
     * Audio and video tracks can be created with names. This feature is useful for categorizing
     * tracks of participants. For example, if one participant publishes a video track with
     * ScreenCapturer and CameraCapturer with the names "screen" and "camera" respectively then
     * other participants can use RemoteVideoTrack#getName to determine which video track is
     * produced from the other participant's screen or camera.
     */
    private static final String LOCAL_AUDIO_TRACK_NAME = "microphone";
    private static final String LOCAL_VIDEO_TRACK_NAME = "camera";

    private static final int PERMISSIONS_REQUEST_CODE = 1;

    private static FakeR FAKE_R;

    /*
     * Access token used to connect. This field will be set either from the console generated token
     * or the request to the token server.
     */
    private String accessToken;
    private String roomId;
    private CallConfig config;

    /*
     * A Room represents communication between a local participant and one or more participants.
     */
    private Room room;
    private LocalParticipant localParticipant;
    private RemoteParticipant remoteParticipant;
    private VideoTrack originalRemoteTrack;
    private List<RemoteParticipant> remoteParticipants = new ArrayList<>();
    private RemoteParticipant mainViewParticipant;

    /*
     * A VideoView receives frames from a local or remote video track and renders them
     * to an associated view.
     */
    private VideoView primaryVideoView;
    private VideoView thumbnailVideoView;
    private List<VideoView> sideViews = new ArrayList<>();
    private int numberActiveVideoTracks = 11;

    private VideoTrack mainViewVideoTrack = null;
    private VideoTrack newMainViewVideoTrack = null;
    /*
     * Android application UI elements
     */
    private CameraCapturerCompat cameraCapturer;
    private LocalAudioTrack localAudioTrack;
    private LocalVideoTrack localVideoTrack;
    // private FloatingActionButton connectActionFab;
    private TextView callEndText;
    private TextView switchCameraActionText;
    private TextView localVideoActionText;
    private TextView muteActionText;
    // private FloatingActionButton switchCameraActionFab;
    // private FloatingActionButton localVideoActionFab;
    // private FloatingActionButton muteActionFab;
    private LinearLayout videoMainLinearLayout;
    // private FloatingActionButton switchAudioActionFab;
    private AudioManager audioManager;
    private String participantIdentity;

    private int previousAudioMode;
    private boolean previousMicrophoneMute;
    private boolean disconnectedFromOnDestroy;
    private VideoRenderer localVideoView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TwilioVideoManager.getInstance().setActionListenerObserver(this);

        FAKE_R = new FakeR(this);

        publishEvent(CallEvent.OPENED);
        setContentView(FAKE_R.getLayout("activity_video"));

        primaryVideoView = findViewById(FAKE_R.getId("primary_video_view"));
        thumbnailVideoView = findViewById(FAKE_R.getId("thumbnail_video_view"));

        // connectActionFab = findViewById(FAKE_R.getId("connect_action_fab"));
        callEndText = findViewById(FAKE_R.getId("call_end_text"));
        switchCameraActionText = findViewById(FAKE_R.getId("switch_camera_action_text"));
        localVideoActionText = findViewById(FAKE_R.getId("local_video_action_text"));
        muteActionText = findViewById(FAKE_R.getId("mute_action_text"));
        videoMainLinearLayout = findViewById(FAKE_R.getId("video_main_linear_layout"));
        // switchAudioActionFab = findViewById(FAKE_R.getId("switch_audio_action_fab"));

        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        /*
         * Needed for setting/abandoning audio focus during call
         */
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);

        Intent intent = getIntent();

        this.accessToken = intent.getStringExtra("token");
        this.roomId = intent.getStringExtra("roomId");
        this.config = (CallConfig) intent.getSerializableExtra("config");

        Log.d(TwilioVideo.TAG, "BEFORE REQUEST PERMISSIONS");
        if (!hasPermissionForCameraAndMicrophone()) {
            Log.d(TwilioVideo.TAG, "REQUEST PERMISSIONS");
            requestPermissions();
        } else {
            Log.d(TwilioVideo.TAG, "PERMISSIONS OK. CREATE LOCAL MEDIA");
            createAudioAndVideoTracks();
            connectToRoom();
        }

        /*
         * Set the initial state of the UI
         */
        initializeUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean permissionsGranted = true;

            for (int grantResult : grantResults) {
                permissionsGranted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }

            if (permissionsGranted) {
                createAudioAndVideoTracks();
                connectToRoom();
            } else {
                publishEvent(CallEvent.PERMISSIONS_REQUIRED);
                TwilioVideoActivity.this.handleConnectionError(config.getI18nConnectionError());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * If the local video track was released when the app was put in the background, recreate.
         */
        if (localVideoTrack == null && hasPermissionForCameraAndMicrophone()) {
            localVideoTrack = LocalVideoTrack.create(this,
                    true,
                    cameraCapturer.getVideoCapturer(),
                    LOCAL_VIDEO_TRACK_NAME);
            localVideoTrack.addRenderer(thumbnailVideoView);

            /*
             * If connected to a Room then share the local video track.
             */
            if (localParticipant != null) {
                localParticipant.publishTrack(localVideoTrack);
            }
        }
    }

    @Override
    protected void onPause() {
        /*
         * Release the local video track before going in the background. This ensures that the
         * camera can be used by other applications while this app is in the background.
         */
        if (localVideoTrack != null) {
            /*
             * If this local video track is being shared in a Room, unpublish from room before
             * releasing the video track. Participants will be notified that the track has been
             * unpublished.
             */
            if (localParticipant != null) {
                localParticipant.unpublishTrack(localVideoTrack);
            }

            localVideoTrack.release();
            localVideoTrack = null;
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {

        /*
         * Always disconnect from the room before leaving the Activity to
         * ensure any memory allocated to the Room resource is freed.
         */
        if (room != null && room.getState() != Room.State.DISCONNECTED) {
            room.disconnect();
            disconnectedFromOnDestroy = true;
        }

        /*
         * Release the local audio and video tracks ensuring any memory allocated to audio
         * or video is freed.
         */
        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }

        publishEvent(CallEvent.CLOSED);

        TwilioVideoManager.getInstance().setActionListenerObserver(null);

        super.onDestroy();
    }

    private boolean hasPermissionForCameraAndMicrophone() {
        int resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultCamera == PackageManager.PERMISSION_GRANTED &&
                resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
                this,
                TwilioVideo.PERMISSIONS_REQUIRED,
                PERMISSIONS_REQUEST_CODE);

    }

    private void createAudioAndVideoTracks() {
        // Share your microphone
        localAudioTrack = LocalAudioTrack.create(this, true, LOCAL_AUDIO_TRACK_NAME);

        // Share your camera
        cameraCapturer = new CameraCapturerCompat(this, getAvailableCameraSource());
        localVideoTrack = LocalVideoTrack.create(this,
                true,
                cameraCapturer.getVideoCapturer(),
                LOCAL_VIDEO_TRACK_NAME);
        this.moveLocalVideoToThumbnailView();
    }

    private CameraSource getAvailableCameraSource() {
        return (CameraCapturer.isSourceAvailable(CameraSource.FRONT_CAMERA)) ?
                (CameraSource.FRONT_CAMERA) :
                (CameraSource.BACK_CAMERA);
    }

    private void connectToRoom() {
        configureAudio(true);
        ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(accessToken)
                .roomName(this.roomId);

        /*
         * Add local audio track to connect options to share with participants.
         */
        if (localAudioTrack != null) {
            connectOptionsBuilder
                    .audioTracks(Collections.singletonList(localAudioTrack));
        }

        /*
         * Add local video track to connect options to share with participants.
         */
        if (localVideoTrack != null) {
            connectOptionsBuilder.videoTracks(Collections.singletonList(localVideoTrack));
        }

        room = Video.connect(this, connectOptionsBuilder.build(), roomListener());
    }

    /*
     * The initial state when there is no active conversation.
     */
    private void initializeUI() {
        setDisconnectAction();

        if (config.getPrimaryColorHex() != null) {
            int primaryColor = Color.parseColor(config.getPrimaryColorHex());
            ColorStateList color = ColorStateList.valueOf(primaryColor);
            // connectActionFab.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        }

        if (config.getSecondaryColorHex() != null) {
            int secondaryColor = Color.parseColor(config.getSecondaryColorHex());
            ColorStateList color = ColorStateList.valueOf(secondaryColor);
            // switchCameraActionFab.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            // localVideoActionFab.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            // muteActionFab.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            // switchAudioActionFab.setBackgroundTintList(color);
        }

        // switchCameraActionFab.show();
        switchCameraActionText.setOnClickListener(switchCameraClickListener());
        // localVideoActionFab.show();
        localVideoActionText.setOnClickListener(localVideoClickListener());
        // muteActionFab.show();
        videoMainLinearLayout.setVisibility(View.VISIBLE);
        muteActionText.setOnClickListener(muteClickListener());
        // switchAudioActionFab.show();
        // switchAudioActionFab.setOnClickListener(switchAudioClickListener());
    }

    /*
     * Build all of the side views (should ideally be refactored to be dynamically generated as needed)
     */
    private void prebuildSideViews() {
        /*
        FrameLayout vc = findViewById(FAKE_R.getId("video_container"));
        for (int i=1; i<=numberActiveVideoTracks; i++) {
            VideoView newView = new VideoView(this);
            float sideViewHeight = convertDpToPixel(96, this);
            float sideViewWidth = convertDpToPixel(96, this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((int) sideViewHeight,(int) sideViewWidth);
            int newSideViewTop = (int) convertDpToPixel(16,this) + (i*((int) sideViewHeight + (int) convertDpToPixel(16,this)));

            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int screenHeight = displayMetrics.heightPixels;
            int screenWidth = displayMetrics.widthPixels;
            int viewLeft = (int) screenWidth - (int) sideViewWidth - (int) convertDpToPixel(16,this);
            params.setMargins(viewLeft, newSideViewTop, (int) convertDpToPixel(16,this),  (int) convertDpToPixel(16,this));
            newView.setLayoutParams(params);
//            newView.setVisibility(View.VISIBLE);
            newView.setVisibility(View.GONE);
//            localVideoTrack.addRenderer(newView);
//            newView.setBackgroundColor(Color.BLACK);
            sideViews.add(newView);
            vc.addView(newView);
        }
        */
    }

    /*
     * Full video view cleanup - remove all renderers from all video tracks
    */
    private void removeAllVideoRenderers() {
        // This is the nuclear option, would prefer something a bit more graceful
        Log.d("remove renderers", "removing all video renderers");
        for (RemoteParticipant participant:remoteParticipants) {
            Log.d("loop over participants", "loop over participants");
            if (participant.getRemoteVideoTracks().size() > 0) {
                Log.d("participant information", participant.getRemoteVideoTracks().size()+" video tracks");
                List<RemoteVideoTrackPublication> remoteVideoTrackPublications = participant.getRemoteVideoTracks();
                for (RemoteVideoTrackPublication remotevideoTrackPublication : remoteVideoTrackPublications) {
                    Log.d("loop over publications", "loop over publications");
                    if (remotevideoTrackPublication.isTrackSubscribed()) {
                        VideoTrack videoTrack = remotevideoTrackPublication.getRemoteVideoTrack();
                        Log.d("videoTrack", videoTrack.getName());
                        for (VideoView view : sideViews) {
                            Log.d("remove side views", "remove side views");
                            videoTrack.removeRenderer(view);
                        }
                    }
                }
            }
        }
        for (VideoView view: sideViews) {
            Log.d("hide side views", "hide side views");
            view.setVisibility(View.GONE);
        }

        Log.d("remove primary view", "remove primary view");
        if (mainViewVideoTrack != null) {
            mainViewVideoTrack.removeRenderer(primaryVideoView);
            mainViewVideoTrack = null;
            primaryVideoView.setBackgroundColor(Color.BLACK);
//            primaryVideoView.setVisibility(View.GONE);
        }
    }

    private void adjustSideViews() {
        Log.i("code branch", "adjustSideViews");
        Log.i("side views", "number side views: "+sideViews.size());
        Log.i("side views", "number particpants: "+remoteParticipants.size());
        if (sideViews.size() < remoteParticipants.size()) {
            increaseSideViews();
        }
        if (sideViews.size() > remoteParticipants.size()) {
            decreaseSideViews();
        }
    }

    private void increaseSideViews() {
        FrameLayout vc = findViewById(FAKE_R.getId("video_container"));
        Log.i("side views", "number side views: "+sideViews.size());
        Log.i("side views", "number particpants: "+remoteParticipants.size());
        for (int i=sideViews.size()+1; i<=remoteParticipants.size(); i++) {
            Log.i("side views", "new side view: "+i);
            VideoView newView = new VideoView(this);
            float sideViewHeight = convertDpToPixel(96, this);
            float sideViewWidth = convertDpToPixel(96, this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((int) sideViewHeight,(int) sideViewWidth);
            int newSideViewTop = (int) convertDpToPixel(16,this) + (i*((int) sideViewHeight + (int) convertDpToPixel(16,this)));

            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int screenHeight = displayMetrics.heightPixels;
            int screenWidth = displayMetrics.widthPixels;
            int viewLeft = (int) screenWidth - (int) sideViewWidth - (int) convertDpToPixel(16,this);
            params.setMargins(viewLeft, newSideViewTop, (int) convertDpToPixel(16,this),  (int) convertDpToPixel(16,this));
            newView.setLayoutParams(params);
//            newView.setVisibility(View.VISIBLE);
            newView.setVisibility(View.GONE);
//            localVideoTrack.addRenderer(newView);
//            newView.setBackgroundColor(Color.BLACK);
            sideViews.add(newView);
            vc.addView(newView);
        }
    }

    private void decreaseSideViews() {
        FrameLayout vc = findViewById(FAKE_R.getId("video_container"));
        for (int i=sideViews.size(); i<=remoteParticipants.size(); i--) {
            VideoView sideView = sideViews.get(i);
            vc.removeView(sideView);
        }
    }

    private void populateVideoViews() {
        int i = 0;
        VideoTrack lastParticipantVideoTrack = null;
        int lastViewIndex = 0;
        primaryVideoView.setMirror(false);
        newMainViewVideoTrack = null;

        for (RemoteParticipant participant: remoteParticipants) {
            if (participant != mainViewParticipant) {
                // get the track that is not named 'screen'
                for (RemoteVideoTrackPublication remoteVideoTrackPublication: participant.getRemoteVideoTracks()) {
                    /*
                     * Only render video tracks that are subscribed to
                     */
                    if (remoteVideoTrackPublication.isTrackSubscribed()) {
                        VideoTrack videoTrack = remoteVideoTrackPublication.getVideoTrack();
                        Log.i("video track", "video track name: |"+videoTrack.getName()+"|");
                        Log.i("side view", "Side view: "+i);
                        if (videoTrack.getName().equals("screen")) {
                            Log.i("screen", "send screen to main view");
                            newMainViewVideoTrack = videoTrack;
                        } else {
                            Log.i("screen", "not screen share, send to side view");
                            videoTrack.addRenderer(sideViews.get(i));
                            sideViews.get(i).setVisibility(View.VISIBLE);
                            if (videoTrack.isEnabled()){
                                sideViews.get(i).setBackgroundColor(Color.TRANSPARENT);
                            } else {
                                sideViews.get(i).setBackgroundColor(Color.BLACK);
                            }
                            lastParticipantVideoTrack = videoTrack;
                        }
                        // Inside here, you could also check to see if somebody is the dominant speaker (after upgrading to a library that supports it)
                    }
                }
            }
            lastViewIndex = i;
            i++;
        }

        // For simplicity's sake, if we did not set the mainViewVideoTrack, then just pull the video track
        // from the last remoteParticipant and use it. You could also pop the first participant off and
        // do the same thing, but this seemed easier.

        if (newMainViewVideoTrack == null && lastParticipantVideoTrack != null) {
            Log.d("last participant", "video track name: "+ lastParticipantVideoTrack.getName());
            Log.d("lastviewindex", "last view index: "+lastViewIndex);
            lastParticipantVideoTrack.removeRenderer(sideViews.get(lastViewIndex));
            sideViews.get(lastViewIndex).setVisibility(View.GONE);
            newMainViewVideoTrack = lastParticipantVideoTrack;
            Log.d("mainViewVideoTrack", "set video track name= "+ newMainViewVideoTrack.getName());
        }

//        Log.d("mainViewVideoTrack", "video track name: "+ mainViewVideoTrack.getName());
        if (newMainViewVideoTrack != null) {
            Log.d("newMainViewVideoTrack", "main video track not null");
            Log.d("newMainViewVideoTrack", "video track name: "+ newMainViewVideoTrack.getName());
            if (mainViewVideoTrack != null && newMainViewVideoTrack != mainViewVideoTrack) {
                if (mainViewVideoTrack.getRenderers().size() > 0) {
                    mainViewVideoTrack.removeRenderer(primaryVideoView);
                }
                VideoView newView = sideViews.get(sideViews.size());
                VideoTrack newVideoTrack = mainViewVideoTrack;
                newVideoTrack.addRenderer(newView);
                newView.setVisibility(View.VISIBLE);
            }
            mainViewVideoTrack = newMainViewVideoTrack;
            mainViewVideoTrack.addRenderer(primaryVideoView);
            primaryVideoView.setVisibility(View.VISIBLE);
            if (mainViewVideoTrack.isEnabled()) {
                primaryVideoView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                primaryVideoView.setBackgroundColor(Color.BLACK);
            }

        }

    }

    /*
     * The actions performed during disconnect.
     */
    private void setDisconnectAction() {
        // connectActionFab.setImageDrawable(ContextCompat.getDrawable(this, FAKE_R.getDrawable("ic_cancel_presentation_24px")));
        // connectActionFab.show();
        // connectActionFab.setOnClickListener(disconnectClickListener());

        // callEndText.setImageDrawable(ContextCompat.getDrawable(this, FAKE_R.getDrawable("ic_cancel_presentation_24px")));
        // callEndText.show();
        callEndText.setOnClickListener(disconnectClickListener());
    }

    private void resetAndRebuildVideoViews() {
        removeAllVideoRenderers();
        populateVideoViews();
    }

    /*
     * Called when participant joins the room
     */
    private void addRemoteParticipant(RemoteParticipant participant) {
        participantIdentity = participant.getIdentity();

        remoteParticipants.add(participant);
        adjustSideViews();

        /*
         * Start listening for participant media events
         */
        participant.setListener(remoteParticipantListener());

        /*
         * Add participant renderer
        if (participant.getRemoteVideoTracks().size() > 0) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    participant.getRemoteVideoTracks().get(0);

            /*
             * Only render video tracks that are subscribed to

            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                addRemoteParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }

        */
    }

    /*
     * Set primary view as renderer for participant video track
     */
    @SuppressLint("LongLogTag")
    private void addRemoteParticipantVideo(VideoTrack videoTrack) {
        /*
        boolean screenShare = false;

        Log.d("originalRemoteTrack", String.valueOf(originalRemoteTrack));
        if (originalRemoteTrack == null) {
            originalRemoteTrack = videoTrack;
        } else {
            originalRemoteTrack.removeRenderer(primaryVideoView);
            screenShare = true;
            originalRemoteTrack = null;
        }
        Log.d("videoTrack", String.valueOf(videoTrack));
        Log.d("videoTrack localVideoTrack", String.valueOf(localVideoTrack));
        Log.d("videoTrack primaryVideoView", String.valueOf(primaryVideoView));
        primaryVideoView.setMirror(false);
        if (screenShare) {
            // originalRemoteTrack.addRenderer(primaryVideoView);
        }
        videoTrack.addRenderer(primaryVideoView);
        primaryVideoView.setVisibility(View.VISIBLE);

     */
    }

    private void moveLocalVideoToThumbnailView() {
        if (thumbnailVideoView.getVisibility() == View.GONE) {
            thumbnailVideoView.setVisibility(View.VISIBLE);
            if (localVideoTrack != null) {
                localVideoTrack.removeRenderer(primaryVideoView);
                localVideoTrack.addRenderer(thumbnailVideoView);
            }
            if (localVideoView != null && thumbnailVideoView != null) {
                localVideoView = thumbnailVideoView;
            }
            thumbnailVideoView.setMirror(cameraCapturer.getCameraSource() ==
                    CameraSource.FRONT_CAMERA);
        }
    }

    /*
     * Called when participant leaves the room
     */
    private void removeRemoteParticipant(RemoteParticipant participant) {
        if (!participant.getIdentity().equals(participantIdentity)) {
            return;
        }

        remoteParticipants.remove(participant);

        adjustSideViews();
        /*
         * Remove participant renderer
         */
        /*
        if (participant.getRemoteVideoTracks().size() > 0) {
            RemoteVideoTrackPublication remoteVideoTrackPublication =
                    participant.getRemoteVideoTracks().get(0);

            /*
             * Remove video only if subscribed to participant track
            if (remoteVideoTrackPublication.isTrackSubscribed()) {
                removeParticipantVideo(remoteVideoTrackPublication.getRemoteVideoTrack());
            }
        }

         */
    }

    private void removeParticipantVideo(VideoTrack videoTrack) {
        /*
        primaryVideoView.setVisibility(View.GONE);
        videoTrack.removeRenderer(primaryVideoView);

         */
    }

    /*
     * Room events listener
     */
    private Room.Listener roomListener() {
        return new Room.Listener() {
            @Override
            public void onConnected(Room room) {
                localParticipant = room.getLocalParticipant();
                publishEvent(CallEvent.CONNECTED);

                final List<RemoteParticipant> remoteParticipants = room.getRemoteParticipants();
                if (remoteParticipants != null && !remoteParticipants.isEmpty()) {
                    for (RemoteParticipant participant: remoteParticipants) {
                        addRemoteParticipant(participant);
                    }
//                    addRemoteParticipant(remoteParticipants.get(0));
                }
            }

            @Override
            public void onConnectFailure(Room room, TwilioException e) {
                publishEvent(CallEvent.CONNECT_FAILURE);
                TwilioVideoActivity.this.handleConnectionError(config.getI18nConnectionError());
            }

            @Override
            public void onReconnecting(@NonNull Room room, @NonNull TwilioException twilioException) {
                publishEvent(CallEvent.RECONNECTING);
            }

            @Override
            public void onReconnected(@NonNull Room room) {
                publishEvent(CallEvent.RECONNECTED);
            }

            @Override
            public void onDisconnected(Room room, TwilioException e) {
                localParticipant = null;
                TwilioVideoActivity.this.room = null;
                // Only reinitialize the UI if disconnect was not called from onDestroy()
                if (!disconnectedFromOnDestroy && e != null) {
                    JSONObject data = null;
                    try {
                        data = new JSONObject();
                        data.put("code", String.valueOf(e.getCode()));
                        data.put("description", e.getExplanation());
                    } catch (JSONException e1) {
                        Log.e(TwilioVideo.TAG, "onDisconnected. Error sending error data");
                    }
                    publishEvent(CallEvent.DISCONNECTED_WITH_ERROR, data);
                    TwilioVideoActivity.this.handleConnectionError(config.getI18nDisconnectedWithError());
                } else {
                    publishEvent(CallEvent.DISCONNECTED);
                }
            }

            @Override
            public void onParticipantConnected(Room room, RemoteParticipant participant) {
                publishEvent(CallEvent.PARTICIPANT_CONNECTED);
                addRemoteParticipant(participant);
            }

            @Override
            public void onParticipantDisconnected(Room room, RemoteParticipant participant) {
                publishEvent(CallEvent.PARTICIPANT_DISCONNECTED);
                removeRemoteParticipant(participant);
            }

            @Override
            public void onRecordingStarted(Room room) {
                /*
                 * Indicates when media shared to a Room is being recorded. Note that
                 * recording is only available in our Group Rooms developer preview.
                 */
                Log.d(TwilioVideo.TAG, "onRecordingStarted");
            }

            @Override
            public void onRecordingStopped(Room room) {
                /*
                 * Indicates when media shared to a Room is no longer being recorded. Note that
                 * recording is only available in our Group Rooms developer preview.
                 */
                Log.d(TwilioVideo.TAG, "onRecordingStopped");
            }
        };
    }

    private RemoteParticipant.Listener remoteParticipantListener() {
        return new RemoteParticipant.Listener() {

            @Override
            public void onAudioTrackPublished(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {
                Log.i(TwilioVideo.TAG, String.format("onAudioTrackPublished: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteAudioTrackPublication: sid=%s, enabled=%b, " +
                                "subscribed=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteAudioTrackPublication.getTrackSid(),
                        remoteAudioTrackPublication.isTrackEnabled(),
                        remoteAudioTrackPublication.isTrackSubscribed(),
                        remoteAudioTrackPublication.getTrackName()));
            }

            @Override
            public void onAudioTrackUnpublished(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {
                Log.i(TwilioVideo.TAG, String.format("onAudioTrackUnpublished: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteAudioTrackPublication: sid=%s, enabled=%b, " +
                                "subscribed=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteAudioTrackPublication.getTrackSid(),
                        remoteAudioTrackPublication.isTrackEnabled(),
                        remoteAudioTrackPublication.isTrackSubscribed(),
                        remoteAudioTrackPublication.getTrackName()));
            }

            @Override
            public void onAudioTrackSubscribed(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication, RemoteAudioTrack remoteAudioTrack) {
                Log.i(TwilioVideo.TAG, String.format("onAudioTrackSubscribed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteAudioTrack: enabled=%b, playbackEnabled=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteAudioTrack.isEnabled(),
                        remoteAudioTrack.isPlaybackEnabled(),
                        remoteAudioTrack.getName()));
                publishEvent(CallEvent.AUDIO_TRACK_ADDED);
            }

            @Override
            public void onAudioTrackSubscriptionFailed(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication, TwilioException twilioException) {
                Log.i(TwilioVideo.TAG, String.format("onAudioTrackSubscriptionFailed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteAudioTrackPublication: sid=%b, name=%s]" +
                                "[TwilioException: code=%d, message=%s]",
                        remoteParticipant.getIdentity(),
                        remoteAudioTrackPublication.getTrackSid(),
                        remoteAudioTrackPublication.getTrackName(),
                        twilioException.getCode(),
                        twilioException.getMessage()));
            }

            @Override
            public void onAudioTrackUnsubscribed(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication, RemoteAudioTrack remoteAudioTrack) {
                Log.i(TwilioVideo.TAG, String.format("onAudioTrackUnsubscribed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteAudioTrack: enabled=%b, playbackEnabled=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteAudioTrack.isEnabled(),
                        remoteAudioTrack.isPlaybackEnabled(),
                        remoteAudioTrack.getName()));
                publishEvent(CallEvent.AUDIO_TRACK_REMOVED);
            }

            @Override
            public void onVideoTrackPublished(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {
                Log.i(TwilioVideo.TAG, String.format("onVideoTrackPublished: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteVideoTrackPublication: sid=%s, enabled=%b, " +
                                "subscribed=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteVideoTrackPublication.getTrackSid(),
                        remoteVideoTrackPublication.isTrackEnabled(),
                        remoteVideoTrackPublication.isTrackSubscribed(),
                        remoteVideoTrackPublication.getTrackName()));
            }

            @Override
            public void onVideoTrackUnpublished(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {
                Log.i(TwilioVideo.TAG, String.format("onVideoTrackUnpublished: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteVideoTrackPublication: sid=%s, enabled=%b, " +
                                "subscribed=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteVideoTrackPublication.getTrackSid(),
                        remoteVideoTrackPublication.isTrackEnabled(),
                        remoteVideoTrackPublication.isTrackSubscribed(),
                        remoteVideoTrackPublication.getTrackName()));
            }

            @Override
            public void onVideoTrackSubscribed(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication, RemoteVideoTrack remoteVideoTrack) {
                Log.i(TwilioVideo.TAG, String.format("onVideoTrackSubscribed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteVideoTrack: enabled=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteVideoTrack.isEnabled(),
                        remoteVideoTrack.getName()));
                publishEvent(CallEvent.VIDEO_TRACK_ADDED);
//                addRemoteParticipantVideo(remoteVideoTrack);
                resetAndRebuildVideoViews();

            }

            @Override
            public void onVideoTrackSubscriptionFailed(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication, TwilioException twilioException) {
                Log.i(TwilioVideo.TAG, String.format("onVideoTrackSubscriptionFailed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteVideoTrackPublication: sid=%b, name=%s]" +
                                "[TwilioException: code=%d, message=%s]",
                        remoteParticipant.getIdentity(),
                        remoteVideoTrackPublication.getTrackSid(),
                        remoteVideoTrackPublication.getTrackName(),
                        twilioException.getCode(),
                        twilioException.getMessage()));
            }

            @Override
            public void onVideoTrackUnsubscribed(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication, RemoteVideoTrack remoteVideoTrack) {
                Log.i(TwilioVideo.TAG, String.format("onVideoTrackUnsubscribed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteVideoTrack: enabled=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteVideoTrack.isEnabled(),
                        remoteVideoTrack.getName()));
                publishEvent(CallEvent.VIDEO_TRACK_REMOVED);
//                removeParticipantVideo(remoteVideoTrack);
                resetAndRebuildVideoViews();
            }

            @Override
            public void onDataTrackPublished(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication) {
                Log.i(TwilioVideo.TAG, String.format("onDataTrackPublished: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteDataTrackPublication: sid=%s, enabled=%b, " +
                                "subscribed=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteDataTrackPublication.getTrackSid(),
                        remoteDataTrackPublication.isTrackEnabled(),
                        remoteDataTrackPublication.isTrackSubscribed(),
                        remoteDataTrackPublication.getTrackName()));
            }

            @Override
            public void onDataTrackUnpublished(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication) {
                Log.i(TwilioVideo.TAG, String.format("onDataTrackUnpublished: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteDataTrackPublication: sid=%s, enabled=%b, " +
                                "subscribed=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteDataTrackPublication.getTrackSid(),
                        remoteDataTrackPublication.isTrackEnabled(),
                        remoteDataTrackPublication.isTrackSubscribed(),
                        remoteDataTrackPublication.getTrackName()));
            }

            @Override
            public void onDataTrackSubscribed(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication, RemoteDataTrack remoteDataTrack) {
                Log.i(TwilioVideo.TAG, String.format("onDataTrackSubscribed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteDataTrack: enabled=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteDataTrack.isEnabled(),
                        remoteDataTrack.getName()));
            }

            @Override
            public void onDataTrackSubscriptionFailed(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication, TwilioException twilioException) {
                Log.i(TwilioVideo.TAG, String.format("onDataTrackSubscriptionFailed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteDataTrackPublication: sid=%b, name=%s]" +
                                "[TwilioException: code=%d, message=%s]",
                        remoteParticipant.getIdentity(),
                        remoteDataTrackPublication.getTrackSid(),
                        remoteDataTrackPublication.getTrackName(),
                        twilioException.getCode(),
                        twilioException.getMessage()));
            }

            @Override
            public void onDataTrackUnsubscribed(RemoteParticipant remoteParticipant, RemoteDataTrackPublication remoteDataTrackPublication, RemoteDataTrack remoteDataTrack) {
                Log.i(TwilioVideo.TAG, String.format("onDataTrackUnsubscribed: " +
                                "[RemoteParticipant: identity=%s], " +
                                "[RemoteDataTrack: enabled=%b, name=%s]",
                        remoteParticipant.getIdentity(),
                        remoteDataTrack.isEnabled(),
                        remoteDataTrack.getName()));
            }

            @Override
            public void onAudioTrackEnabled(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onAudioTrackDisabled(RemoteParticipant remoteParticipant, RemoteAudioTrackPublication remoteAudioTrackPublication) {

            }

            @Override
            public void onVideoTrackEnabled(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {
                resetAndRebuildVideoViews();
            }

            @Override
            public void onVideoTrackDisabled(RemoteParticipant remoteParticipant, RemoteVideoTrackPublication remoteVideoTrackPublication) {
                resetAndRebuildVideoViews();
            }
        };
    }

    private View.OnClickListener disconnectClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (config.isHangUpInApp()) {
                    // Propagating the event to the web side in order to allow developers to do something else before disconnecting the room
                    publishEvent(CallEvent.HANG_UP);
                } else {
                    onDisconnect();
                }
            }
        };
    }

    private View.OnClickListener switchCameraClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cameraCapturer != null) {
                    CameraSource cameraSource = cameraCapturer.getCameraSource();
                    cameraCapturer.switchCamera();
                    if (thumbnailVideoView.getVisibility() == View.VISIBLE) {
                        thumbnailVideoView.setMirror(cameraSource == CameraSource.BACK_CAMERA);
                    } else {
                        primaryVideoView.setMirror(cameraSource == CameraSource.BACK_CAMERA);
                    }
                }
            }
        };
    }

    /*private View.OnClickListener switchAudioClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (audioManager.isSpeakerphoneOn()) {
                    audioManager.setSpeakerphoneOn(false);
                } else {
                    audioManager.setSpeakerphoneOn(true);

                }
                int icon = audioManager.isSpeakerphoneOn() ?
                        FAKE_R.getDrawable("ic_phonelink_ring_white_24dp") : FAKE_R.getDrawable("ic_volume_headhphones_white_24dp");
                 switchAudioActionFab.setImageDrawable(ContextCompat.getDrawable(
                        TwilioVideoActivity.this, icon));
            }
        };
    }*/

    private View.OnClickListener localVideoClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /*
                 * Enable/disable the local video track
                 */
                if (localVideoTrack != null) {
                    boolean enable = !localVideoTrack.isEnabled();
                    localVideoTrack.enable(enable);
                    int icon;
                    if (enable) {
                        // icon = FAKE_R.getDrawable("ic_videocam_green_24px");
                        // switchCameraActionFab.show();
                    } else {
                        // icon = FAKE_R.getDrawable("ic_videocam_off_red_24px");
                        // switchCameraActionFab.hide();
                    }

                    // localVideoActionFab.setImageDrawable(
                        //    ContextCompat.getDrawable(TwilioVideoActivity.this, icon));
                }
            }
        };
    }

    private View.OnClickListener muteClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /*
                 * Enable/disable the local audio track. The results of this operation are
                 * signaled to other Participants in the same Room. When an audio track is
                 * disabled, the audio is muted.
                 */
                if (localAudioTrack != null) {
                    boolean enable = !localAudioTrack.isEnabled();
                    localAudioTrack.enable(enable);

                    if (enable) {
                        muteActionText.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_mic_green_24px, 0, 0);
                        muteActionText.setText("Mute");
                    } else {
                        muteActionText.setCompoundDrawablesWithIntrinsicBounds(0,  R.drawable.ic_mute,0 , 0);
                        muteActionText.setText("Unmute");
                    }

                }
            }
        };
    }

    private void configureAudio(boolean enable) {
        if (enable) {
            previousAudioMode = audioManager.getMode();
            // Request audio focus before making any device switch
            requestAudioFocus();
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required
             * to be in this mode when playout and/or recording starts for the best
             * possible VoIP performance. Some devices have difficulties with
             * speaker mode if this is not set.
             */
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            /*
             * Always disable microphone mute during a WebRTC call.
             */
            previousMicrophoneMute = audioManager.isMicrophoneMute();
            audioManager.setMicrophoneMute(false);
        } else {
            audioManager.setMode(previousAudioMode);
            audioManager.abandonAudioFocus(null);
            audioManager.setMicrophoneMute(previousMicrophoneMute);
        }
    }

    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFocusRequest focusRequest =
                    new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener(
                                    new AudioManager.OnAudioFocusChangeListener() {
                                        @Override
                                        public void onAudioFocusChange(int i) {
                                        }
                                    })
                            .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private void handleConnectionError(String message) {
        if (config.isHandleErrorInApp()) {
            Log.i(TwilioVideo.TAG, "Error handling disabled for the plugin. This error should be handled in the hybrid app");
            this.finish();
            return;
        }
        Log.i(TwilioVideo.TAG, "Connection error handled by the plugin");
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
                .setCancelable(false)
                .setPositiveButton(config.getI18nAccept(), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        TwilioVideoActivity.this.finish();
                    }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }


    @Override
    public void onDisconnect() {
        /*
         * Disconnect from room
         */
        if (room != null) {
            room.disconnect();
        }

        finish();
    }

    @Override
    public void finish() {
        configureAudio(false);
        super.finish();
        overridePendingTransition(0, 0);
    }

    private void publishEvent(CallEvent event) {
        TwilioVideoManager.getInstance().publishEvent(event);
    }

    private void publishEvent(CallEvent event, JSONObject data) {
        TwilioVideoManager.getInstance().publishEvent(event, data);
    }


    /**
     * This method converts device specific pixels to density independent pixels.
     *
     * @param px A value in px (pixels) unit. Which we need to convert into db
     * @param context Context to get resources and device specific display metrics
     * @return A float value to represent dp equivalent to px value
     */
    public static float convertPixelsToDp(float px, Context context){
        return px / ((float) context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    /**
     * This method converts dp unit to equivalent pixels, depending on device density.
     *
     * @param dp A value in dp (density independent pixels) unit. Which we need to convert into pixels
     * @param context Context to get resources and device specific display metrics
     * @return A float value to represent px equivalent to dp depending on device density
     */
    public static float convertDpToPixel(float dp, Context context){
        return dp * ((float) context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }
}
