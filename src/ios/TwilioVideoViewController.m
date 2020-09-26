#import "TwilioVideoViewController.h"

// CALL EVENTS
NSString *const OPENED = @"OPENED";
NSString *const CONNECTED = @"CONNECTED";
NSString *const CONNECT_FAILURE = @"CONNECT_FAILURE";
NSString *const DISCONNECTED = @"DISCONNECTED";
NSString *const DISCONNECTED_WITH_ERROR = @"DISCONNECTED_WITH_ERROR";
NSString *const PARTICIPANT_CONNECTED = @"PARTICIPANT_CONNECTED";
NSString *const PARTICIPANT_DISCONNECTED = @"PARTICIPANT_DISCONNECTED";
NSString *const AUDIO_TRACK_ADDED = @"AUDIO_TRACK_ADDED";
NSString *const AUDIO_TRACK_REMOVED = @"AUDIO_TRACK_REMOVED";
NSString *const VIDEO_TRACK_ADDED = @"VIDEO_TRACK_ADDED";
NSString *const VIDEO_TRACK_REMOVED = @"VIDEO_TRACK_REMOVED";
NSString *const PERMISSIONS_REQUIRED = @"PERMISSIONS_REQUIRED";
NSString *const HANG_UP = @"HANG_UP";
NSString *const CLOSED = @"CLOSED";
NSString *const MUTED = @"MUTED";
NSString *const UNMUTED = @"UNMUTED";

@implementation TwilioVideoViewController

#pragma mark - UIViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    
    [[TwilioVideoManager getInstance] setActionDelegate:self];

    [[TwilioVideoManager getInstance] publishEvent: OPENED];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
    
    [self logMessage:[NSString stringWithFormat:@"TwilioVideo v%ld", (long)[TwilioVideoSDK version]]];
    
//    [TwilioVideoSDK setLogLevel:TVILogLevelAll];
    // Configure access token for testing. Create one manually in the console
    // at https://www.twilio.com/console/video/runtime/testing-tools
    self.accessToken = @"TWILIO_ACCESS_TOKEN";
    
    // Preview our local camera track in the local video preview view.
    [self startPreview];
    
    [self.micButton setImage:[UIImage imageNamed:@"mute"] forState: UIControlStateNormal];
    [self.micButton setImage:[UIImage imageNamed:@"mute"] forState: UIControlStateSelected];
    [self.videoButton setImage:[UIImage imageNamed:@"video_camera"] forState: UIControlStateNormal];
    [self.videoButton setImage:[UIImage imageNamed:@"video_camera"] forState: UIControlStateSelected];
    
    
    self.disconnectButton.backgroundColor = [UIColor clearColor];
    self.micButton.backgroundColor = [UIColor clearColor];
    self.videoButton.backgroundColor = [UIColor clearColor];
    self.cameraSwitchButton.backgroundColor = [UIColor clearColor];
    
    self.numberActiveVideoTracks = 5;
}

#pragma mark - Public

- (void)connectToRoom:(NSString*)room token:(NSString *)token {
    self.roomName = room;
    self.accessToken = token;
    [self showRoomUI:YES];

    [TwilioVideoPermissions requestRequiredPermissions:^(BOOL grantedPermissions) {
         if (grantedPermissions) {
             [self doConnect];
         } else {
             [[TwilioVideoManager getInstance] publishEvent: PERMISSIONS_REQUIRED];
             [self handleConnectionError: [self.config i18nConnectionError]];
         }
    }];
}

- (IBAction)disconnectButtonPressed:(id)sender {
    if ([self.config hangUpInApp]) {
        [[TwilioVideoManager getInstance] publishEvent: HANG_UP];
    } else {
        [self onDisconnect];
    }
}

- (IBAction)micButtonPressed:(id)sender {
    // We will toggle the mic to mute/unmute and change the title according to the user action.
    
    if (self.localAudioTrack) {
        self.localAudioTrack.enabled = !self.localAudioTrack.isEnabled;
        // If audio not enabled, mic is muted and button crossed out
        [self.micButton setSelected: !self.localAudioTrack.isEnabled];
        if (self.localAudioTrack.enabled) {
            [[TwilioVideoManager getInstance] publishEvent: UNMUTED];
        } else {
            [[TwilioVideoManager getInstance] publishEvent: MUTED];
        }
    }
}

- (IBAction)cameraSwitchButtonPressed:(id)sender {
    [self flipCamera];
}

- (IBAction)videoButtonPressed:(id)sender {
    if(self.localVideoTrack){
        self.localVideoTrack.enabled = !self.localVideoTrack.isEnabled;
        [self.videoButton setSelected: !self.localVideoTrack.isEnabled];
    }
}

#pragma mark - Private

- (BOOL)isSimulator {
#if TARGET_IPHONE_SIMULATOR
    return YES;
#endif
    return NO;
}

- (void)startPreview {
    // TVICameraSource is not supported with the Simulator.
    if ([self isSimulator]) {
        [self.previewView removeFromSuperview];
        return;
    }

    AVCaptureDevice *frontCamera = [TVICameraSource captureDeviceForPosition:AVCaptureDevicePositionFront];
    AVCaptureDevice *backCamera = [TVICameraSource captureDeviceForPosition:AVCaptureDevicePositionBack];

    if (frontCamera != nil || backCamera != nil) {
        self.camera = [[TVICameraSource alloc] initWithDelegate:self];
        self.localVideoTrack = [TVILocalVideoTrack trackWithSource:self.camera
                                                           enabled:YES
                                                              name:@"Camera"];
        // Add renderer to video track for local preview
        [self.localVideoTrack addRenderer:self.previewView];
        [self logMessage:@"Video track created"];

        if (frontCamera != nil && backCamera != nil) {
            UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self
                                                                                  action:@selector(flipCamera)];
            [self.previewView addGestureRecognizer:tap];
        }

        [self.camera startCaptureWithDevice:frontCamera != nil ? frontCamera : backCamera
                                 completion:^(AVCaptureDevice *device, TVIVideoFormat *format, NSError *error) {
                                     if (error != nil) {
                                         [self logMessage:[NSString stringWithFormat:@"Start capture failed with error.\ncode = %lu error = %@", error.code, error.localizedDescription]];
                                     } else {
                                         self.previewView.mirror = (device.position == AVCaptureDevicePositionFront);
                                     }
                                 }];
    } else {
        [self logMessage:@"No front or back capture device found!"];
    }
    
    // The following code is depecrated from the 2.5.6 upgrade to 3.6 - leaving just in case something is
    // needed from it.
    // TVICameraCapturer is not supported with the Simulator.
//    if ([self isSimulator]) {
//        [self.previewView removeFromSuperview];
//        return;
//    }
//
//    self.camera = [[TVICameraCapturer alloc] initWithSource:TVICameraCaptureSourceFrontCamera delegate:self];
//    self.localVideoTrack = [TVILocalVideoTrack trackWithCapturer:self.camera
//                                                         enabled:YES
//                                                     constraints:nil
//                                                            name:@"Camera"];
//    if (!self.localVideoTrack) {
//        [self logMessage:@"Failed to add video track"];
//    } else {
//        // Add renderer to video track for local preview
//        [self.localVideoTrack addRenderer:self.previewView];
//
//        [self logMessage:@"Video track created"];
//
//        UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self
//                                                                              action:@selector(flipCamera)];
//
//        self.videoButton.hidden = NO;
//        self.cameraSwitchButton.hidden = NO;
//        [self.previewView addGestureRecognizer:tap];
//    }
}

- (void)flipCamera {
    AVCaptureDevice *newDevice = nil;

    if (self.camera.device.position == AVCaptureDevicePositionFront) {
        newDevice = [TVICameraSource captureDeviceForPosition:AVCaptureDevicePositionBack];
    } else {
        newDevice = [TVICameraSource captureDeviceForPosition:AVCaptureDevicePositionFront];
    }

    if (newDevice != nil) {
        [self.camera selectCaptureDevice:newDevice completion:^(AVCaptureDevice *device, TVIVideoFormat *format, NSError *error) {
            if (error != nil) {
                [self logMessage:[NSString stringWithFormat:@"Error selecting capture device.\ncode = %lu error = %@", error.code, error.localizedDescription]];
            } else {
                self.previewView.mirror = (device.position == AVCaptureDevicePositionFront);
            }
        }];
    }
//    if (self.camera.source == TVICameraCaptureSourceFrontCamera) {
//        [self.camera selectSource:TVICameraCaptureSourceBackCameraWide];
//    } else {
//        [self.camera selectSource:TVICameraCaptureSourceFrontCamera];
//    }
}

- (void)prepareLocalMedia {
    
    // We will share local audio and video when we connect to room.
    
    // Create an audio track.
    if (!self.localAudioTrack) {
        self.localAudioTrack = [TVILocalAudioTrack trackWithOptions:nil
                                                            enabled:YES
                                                               name:@"Microphone"];
        
        if (!self.localAudioTrack) {
            [self logMessage:@"Failed to add audio track"];
        }
    }
    
    // Create a video track which captures from the camera.
    if (!self.localVideoTrack) {
        [self startPreview];
    }
}

- (void)doConnect {
    if ([self.accessToken isEqualToString:@"TWILIO_ACCESS_TOKEN"]) {
        [self logMessage:@"Please provide a valid token to connect to a room"];
        return;
    }
    
    // Prepare local media which we will share with Room Participants.
    [self prepareLocalMedia];
    
    TVIConnectOptions *connectOptions = [TVIConnectOptions optionsWithToken:self.accessToken
                                                                      block:^(TVIConnectOptionsBuilder * _Nonnull builder) {
                                                                          builder.roomName = self.roomName;
                                                                          // Use the local media that we prepared earlier.
                                                                          builder.audioTracks = self.localAudioTrack ? @[ self.localAudioTrack ] : @[ ];
                                                                          builder.videoTracks = self.localVideoTrack ? @[ self.localVideoTrack ] : @[ ];
                                                                      }];
    
    // Connect to the Room using the options we provided.
    self.room = [TwilioVideoSDK connectWithOptions:connectOptions delegate:self];
    
    [self logMessage:@"Attempting to connect to room"];
}

- (void)setupMainRemoteView {
    // Creating `TVIVideoView` programmatically
    TVIVideoView *remoteView = [[TVIVideoView alloc] init];
    [self logMessage:@"setupMainRemoteView"];
    // `TVIVideoView` supports UIViewContentModeScaleToFill, UIViewContentModeScaleAspectFill and UIViewContentModeScaleAspectFit
    // UIViewContentModeScaleAspectFit is the default mode when you create `TVIVideoView` programmatically.
    remoteView.contentMode = UIViewContentModeScaleAspectFill;

    [self.view insertSubview:remoteView atIndex:0];
    self.mainRemoteView = remoteView;
    
    NSLayoutConstraint *centerX = [NSLayoutConstraint constraintWithItem:self.mainRemoteView
                                                               attribute:NSLayoutAttributeCenterX
                                                               relatedBy:NSLayoutRelationEqual
                                                                  toItem:self.view
                                                               attribute:NSLayoutAttributeCenterX
                                                              multiplier:1
                                                                constant:0];
    [self.view addConstraint:centerX];
    NSLayoutConstraint *centerY = [NSLayoutConstraint constraintWithItem:self.mainRemoteView
                                                               attribute:NSLayoutAttributeCenterY
                                                               relatedBy:NSLayoutRelationEqual
                                                                  toItem:self.view
                                                               attribute:NSLayoutAttributeCenterY
                                                              multiplier:1
                                                                constant:0];
    [self.view addConstraint:centerY];
    NSLayoutConstraint *width = [NSLayoutConstraint constraintWithItem:self.mainRemoteView
                                                             attribute:NSLayoutAttributeWidth
                                                             relatedBy:NSLayoutRelationEqual
                                                                toItem:self.view
                                                             attribute:NSLayoutAttributeWidth
                                                            multiplier:1
                                                              constant:0];
    [self.view addConstraint:width];
    NSLayoutConstraint *height = [NSLayoutConstraint constraintWithItem:self.mainRemoteView
                                                              attribute:NSLayoutAttributeHeight
                                                              relatedBy:NSLayoutRelationEqual
                                                                 toItem:self.view
                                                              attribute:NSLayoutAttributeHeight
                                                             multiplier:1
                                                               constant:0];
    [self.view addConstraint:height];
    [self logMessage:@"finish setupMainRemoteView"];
}

- (void)setupSideRemoteView {
//    int height = 160;
//    int width = 120;
    // Creating `TVIVideoView` programmatically
    TVIVideoView *remoteView = [[TVIVideoView alloc] init];
        
    // `TVIVideoView` supports UIViewContentModeScaleToFill, UIViewContentModeScaleAspectFill and UIViewContentModeScaleAspectFit
    // UIViewContentModeScaleAspectFit is the default mode when you create `TVIVideoView` programmatically.
    remoteView.contentMode = UIViewContentModeScaleAspectFill;

    [self.view insertSubview:remoteView atIndex:1];
    [self.remoteViews addObject:remoteView];
    
    int newRemoteViewIndex = self.remoteViews.count - 1.0;
    unsigned long nextY = 170 * newRemoteViewIndex;
    nextY += 200;
    
    [self logMessage:[NSString stringWithFormat:@"%i: newRemoteViewIndex", newRemoteViewIndex]];
    [self logMessage:[NSString stringWithFormat:@"%lu: nextY", nextY]];
    NSLayoutConstraint *rightX = [NSLayoutConstraint constraintWithItem:self.remoteViews[newRemoteViewIndex]
                                                               attribute:NSLayoutAttributeRight
                                                               relatedBy:NSLayoutRelationEqual
                                                                 toItem:self.view
                                                               attribute:NSLayoutAttributeRight
                                                              multiplier:1
                                                                constant:-15];
    [self.view addConstraint:rightX];
    NSLayoutConstraint *topY = [NSLayoutConstraint constraintWithItem:self.remoteViews[newRemoteViewIndex]
                                                                attribute:NSLayoutAttributeTop
                                                                relatedBy:NSLayoutRelationEqual
                                                                   toItem:self.view
                                                                attribute:NSLayoutAttributeTop
                                                                multiplier:1
                                                                 constant:nextY];
    [self.view addConstraint:topY];
    NSLayoutConstraint *width = [NSLayoutConstraint constraintWithItem:self.remoteViews[newRemoteViewIndex]
                                                             attribute:NSLayoutAttributeWidth
                                                             relatedBy:NSLayoutRelationEqual
                                                                toItem:nil
                                                             attribute:NSLayoutAttributeNotAnAttribute
                                                            multiplier:0
                                                              constant:120];
    [self.remoteViews[newRemoteViewIndex] addConstraint:width];
    NSLayoutConstraint *height = [NSLayoutConstraint constraintWithItem:self.remoteViews[newRemoteViewIndex]
                                                              attribute:NSLayoutAttributeHeight
                                                              relatedBy:NSLayoutRelationEqual
                                                                 toItem:nil
                                                                 attribute:NSLayoutAttributeNotAnAttribute
                                                             multiplier:0
                                                               constant:160];
    [self.remoteViews[newRemoteViewIndex] addConstraint:height];
}

// Reset the client ui status
- (void)showRoomUI:(BOOL)inRoom {
    self.micButton.hidden = !inRoom;
    [UIApplication sharedApplication].idleTimerDisabled = inRoom;
}

- (void)cleanupRemoteParticipants {
    [self.remoteParticipants removeAllObjects];
    [self.remoteViewsParticipants removeAllObjects];
}

- (void)logMessage:(NSString *)msg {
    NSLog(@"%@", msg);
}

- (void)handleConnectionError: (NSString*)message {
    if ([self.config handleErrorInApp]) {
        [self logMessage: @"Error handling disabled for the plugin. This error should be handled in the hybrid app"];
        [self dismiss];
        return;
    }
    [self logMessage: @"Connection error handled by the plugin"];
    UIAlertController * alert = [UIAlertController
                                 alertControllerWithTitle:NULL
                                 message: message
                                 preferredStyle:UIAlertControllerStyleAlert];
    
    //Add Buttons
    
    UIAlertAction* yesButton = [UIAlertAction
                                actionWithTitle:[self.config i18nAccept]
                                style:UIAlertActionStyleDefault
                                handler: ^(UIAlertAction * action) {
                                    [self dismiss];
                                }];
    
    [alert addAction:yesButton];
    [self presentViewController:alert animated:YES completion:nil];
}

- (void) dismiss {
    [[TwilioVideoManager getInstance] publishEvent: CLOSED];
    [self dismissViewControllerAnimated:NO completion:nil];
}

- (void) updateRemoteVideoTrack:(TVIVideoTrack *)videoTrack
                  toParticipant:(TVIRemoteParticipant *)participant
                         action:(NSString *)action {
    // only setting views for up to 5 participants at a time
    int activeVideoTracks = 5;
    
    // initialize our arrays if they don't already exist
    if (!self.remoteViews) self.remoteViews = [[NSMutableArray alloc] init];
    if (!self.videoTracks) self.videoTracks = [[NSMutableArray alloc] init];
    
    if (self.mainRemoteView == nil) {
        [self logMessage: [NSString stringWithFormat:@"update - mainRemoteView is nil. set it up"]];
        [self setupMainRemoteView];
        [self logMessage: [NSString stringWithFormat:@"after call to setupMainRemoteView"]];
    }
    [self logMessage: [NSString stringWithFormat:@"number of self.remoteViews: %lu", (unsigned long)self.remoteViews.count]];
    [self logMessage: [NSString stringWithFormat:@"self.numberActiveVideoTracks: %lu", self.numberActiveVideoTracks]];
    if (self.remoteViews.count < self.numberActiveVideoTracks - 1) {
        for (int i=0; i<self.numberActiveVideoTracks; i++) {
            [self logMessage: [NSString stringWithFormat:@"create side remote view %i", i]];
            [self setupSideRemoteView];
        }
    }
    
    [self logMessage:[NSString stringWithFormat:@"%@ remote video track", action]];
    [self logMessage:[NSString stringWithFormat:@"%lu remote participants", self.remoteParticipants.count]];
    [self logMessage: [NSString stringWithFormat:@"%lu - remoteVideoTracks for participant %@. Videotrack name: %@", participant.remoteVideoTracks.count, participant.identity, videoTrack.name]];

    // remoteParticipant[0] needs to go to MainView first, before anything else happens.
    
    
    
    if ([videoTrack.name  isEqual: @"screen"]) {
        // We are working with a screenshare track
        if ([action  isEqual: @"add"]) {
            [self logMessage:[NSString stringWithFormat:@"add screenshare"]];
            [self updateMainRemoteView:videoTrack];
        } else {
            [self logMessage:[NSString stringWithFormat:@"remove screenshare"]];
            [self clearMainRemoteView];
        }
    } else {
        if ([action  isEqual: @"add"]) {
            [self logMessage:[NSString stringWithFormat:@"add regular video track"]];
            if (self.videoTracks.count < activeVideoTracks) {
                [self.videoTracks addObject:videoTrack];
            }
        } else {
            [self removeVideoTrack:videoTrack];
            [self.videoTracks removeObject:videoTrack];
        }
    }
    [self logMessage:[NSString stringWithFormat:@"update all remote views"]];
    [self updateRemoteParticipantViews];
}

- (void) removeVideoTrack:(TVIVideoTrack *)videoTrack {
    int videoTrackIndex = 0;
    for (TVIVideoTrack *videoTrackCheck in self.videoTracks) {
        if (videoTrackCheck != videoTrack) {
            videoTrackIndex++;
        }
    }
    [videoTrack removeRenderer:self.remoteViews[videoTrackIndex]];
}

- (void) removeAllVideoTracks {
    [self logMessage: [NSString stringWithFormat:@"remove all remote views."]];
    int remoteViewIndex = 0;
    [self logMessage: [NSString stringWithFormat:@"%lu remote views", self.remoteViews.count]];
    [self logMessage: [NSString stringWithFormat:@"%lu video tracks", self.videoTracks.count]];
    for (TVIVideoView *remoteView in self.remoteViews) {
        if (remoteViewIndex < self.videoTracks.count) {
            [self logMessage: [NSString stringWithFormat:@"remoteViewIndex: %i", remoteViewIndex]];
            if ([self.currentMainVideoTrack.name isEqual: @"screen"]) {
                // we just started screenshare, so videoTrack[1] is currently at remoteViews[0];
                if (remoteViewIndex > 0) {
                    [self.videoTracks[remoteViewIndex] removeRenderer:self.remoteViews[remoteViewIndex - 1]];
                }
            } else {
                // we just ended screenshare, so videoTrack[0] is currently at remoteViews[0];
                [self.videoTracks[remoteViewIndex] removeRenderer:self.remoteViews[remoteViewIndex]];
            }
        }
        remoteViewIndex++;
    }
}

- (void) updateRemoteParticipantViews {
    unsigned long videoTrackIndex = 0;

    if (self.videoTracks.count == self.numberActiveVideoTracks) {
        // if we already have enough video tracks, then exit
        return;
    }
    if (self.remoteViews.count > 0) {
        [self removeAllVideoTracks];
        [self logMessage: [NSString stringWithFormat:@"video tracks removed."]];
    }
    
    if (![self.currentMainVideoTrack.name isEqual: @"screen"]) {
        [self clearMainRemoteView];
    }
    
    [self logMessage: [NSString stringWithFormat:@"add video tracks."]];
    for (TVIVideoTrack *videoTrack in self.videoTracks) {
        [self logMessage: [NSString stringWithFormat:@"remoteViewIndex: %lu", videoTrackIndex]];
        // which view do we need to send the videoTrack to?
        TVIVideoView *view = [self getTargetViewForAdd:&videoTrackIndex];
        if (view == self.mainRemoteView) {
            [self logMessage: [NSString stringWithFormat:@"send to mainRemoteView."]];
        } else {
            [self logMessage: [NSString stringWithFormat:@"send to sideView."]];
        }
        if (view == self.mainRemoteView) {
            [self updateMainRemoteView:videoTrack];
        } else {
            [self logMessage: [NSString stringWithFormat:@"add remote view."]];
            [videoTrack addRenderer:view];
        }
        videoTrackIndex++;
    }
}

- (void) clearMainRemoteView {
    [self.currentMainVideoTrack removeRenderer:self.mainRemoteView];
    self.currentMainVideoTrack = nil;
}

- (void) updateMainRemoteView:(TVIVideoTrack *)videoTrack {
    [self logMessage: [NSString stringWithFormat:@"Setting main remote view."]];
    
    if (self.currentMainVideoTrack != nil) {
        [self logMessage: [NSString stringWithFormat:@"currentMainVideoTrack exists."]];
        [self.currentMainVideoTrack removeRenderer:self.mainRemoteView];
        [self logMessage: [NSString stringWithFormat:@"currentMainVideoTrack renderer removed."]];
    } else {
        [self logMessage: [NSString stringWithFormat:@"currentMainVideoTrack doesn't exist."]];
    }
    [self logMessage: [NSString stringWithFormat:@"currentMainVideoTrack updated."]];
    self.currentMainVideoTrack = videoTrack;
    
    [self logMessage: [NSString stringWithFormat:@"now add renderer."]];
    [self logMessage: [NSString stringWithFormat:@"videoTrack = %@", videoTrack.name]];
    [videoTrack addRenderer:self.mainRemoteView];
}

- (TVIVideoView *) getTargetViewForAdd:(unsigned long *)videoTrackIndex {
    if (self.currentMainVideoTrack == nil) {
        return self.mainRemoteView;
    }
    return [self getTargetSideView:videoTrackIndex];
}

- (TVIVideoView *) getTargetSideView:(unsigned long *)videoTrackIndex {
    // if there is a screenshare in the main view, then self.videoTracks[0] should be in a side view.
    // if there is not a screenshare in the main view, then side view starts at self.videoTracks[1];
    if ([self.currentMainVideoTrack.name  isEqual: @"screen"]) {
        [self logMessage: [NSString stringWithFormat:@"we are doing screen share, so use remoteView"]];
        [self logMessage: [NSString stringWithFormat:@"send back remoteView %lu", *videoTrackIndex]];
        return self.remoteViews[*videoTrackIndex];
    } else {
        [self logMessage: [NSString stringWithFormat:@"we are not doing screen share, so use remoteView - 1"]];
        [self logMessage: [NSString stringWithFormat:@"send back remoteView %lu", *videoTrackIndex - 1]];
        return self.remoteViews[*videoTrackIndex - 1];
    }
}

- (void) removeRemoteParticipant:(TVIRemoteParticipant *)participant {
        [self.remoteParticipants removeObject:participant];
        [self logMessage:[NSString stringWithFormat:@"Removed %@ - %lu participants left", participant.identity, (unsigned long)[self.remoteParticipants count]]];
}

#pragma mark - TVIRoomDelegate

- (void) onDisconnect {
    if (self.room != NULL) {
        [self.room disconnect];
    }
    [self cleanupRemoteParticipants];
}

#pragma mark - TVIRoomDelegate

- (void)didConnectToRoom:(TVIRoom *)room {
    [self logMessage:[NSString stringWithFormat:@"Connected to room %@ as %@", room.name, room.localParticipant.identity]];
    [[TwilioVideoManager getInstance] publishEvent: CONNECTED];
    [self logMessage:[NSString stringWithFormat:@"Connected to room with %lu remote participants", room.remoteParticipants.count]];
    for (TVIRemoteParticipant *participant in room.remoteParticipants) {
        participant.delegate = self;
        [self.remoteParticipants addObject:participant];
    }
    
//    if (room.remoteParticipants.count > 0) {
//        self.remoteParticipant = room.remoteParticipants[0];
//        self.remoteParticipant.delegate = self;
//    }
}

- (void)room:(TVIRoom *)room didDisconnectWithError:(nullable NSError *)error {
    [self logMessage:[NSString stringWithFormat:@"Disconnected from room %@, error = %@", room.name, error]];
    
    [self cleanupRemoteParticipants];
    self.room = nil;
    
    [self showRoomUI:NO];
    if (error != NULL) {
        [[TwilioVideoManager getInstance] publishEvent:DISCONNECTED_WITH_ERROR with:@{ @"code": [NSString stringWithFormat:@"%ld",[error code]] }];
        [self handleConnectionError: [self.config i18nDisconnectedWithError]];
    } else {
        [[TwilioVideoManager getInstance] publishEvent: DISCONNECTED];
        [self dismiss];
    }
}

- (void)room:(TVIRoom *)room didFailToConnectWithError:(nonnull NSError *)error{
    [self logMessage:[NSString stringWithFormat:@"Failed to connect to room, error = %@", error]];
    [[TwilioVideoManager getInstance] publishEvent: CONNECT_FAILURE];
    
    self.room = nil;
    
    [self showRoomUI:NO];
    [self handleConnectionError: [self.config i18nConnectionError]];
}

- (void)room:(TVIRoom *)room participantDidConnect:(TVIRemoteParticipant *)participant {
    if (!self.remoteParticipants) self.remoteParticipants = [[NSMutableArray alloc] init];
    participant.delegate = self;
    [self.remoteParticipants addObject:participant];
    [self logMessage:[NSString stringWithFormat:@"Participant %@ connected with %lu audio and %lu video tracks. %lu remote participants in room. %lu remote participants locally (self).",
    participant.identity,
    (unsigned long)[participant.audioTracks count],
    (unsigned long)[participant.videoTracks count],
    room.remoteParticipants.count,
    self.remoteParticipants.count]];
//    if (!self.remoteParticipant) {
//        self.remoteParticipant = participant;
//        self.remoteParticipant.delegate = self;
//    }
    [[TwilioVideoManager getInstance] publishEvent: PARTICIPANT_CONNECTED];
}

- (void)room:(TVIRoom *)room participantDidDisconnect:(TVIRemoteParticipant *)participant {
//    [self cleanupRemoteParticipant];
    [self removeRemoteParticipant:participant];
    [self logMessage:[NSString stringWithFormat:@"Room %@ participant %@ disconnected", room.name, participant.identity]];
    [[TwilioVideoManager getInstance] publishEvent: PARTICIPANT_DISCONNECTED];
}


#pragma mark - TVIRemoteParticipantDelegate

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
      publishedVideoTrack:(TVIRemoteVideoTrackPublication *)publication {
    
    // Remote Participant has offered to share the video Track.
    
    [self logMessage:[NSString stringWithFormat:@"Participant %@ published %@ video track .",
                      participant.identity, publication.trackName]];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
    unpublishedVideoTrack:(TVIRemoteVideoTrackPublication *)publication {
    
    // Remote Participant has stopped sharing the video Track.
    
    [self logMessage:[NSString stringWithFormat:@"Participant %@ unpublished %@ video track.",
                      participant.identity, publication.trackName]];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
      publishedAudioTrack:(TVIRemoteAudioTrackPublication *)publication {
    
    // Remote Participant has offered to share the audio Track.
    
    [self logMessage:[NSString stringWithFormat:@"Participant %@ published %@ audio track.",
                      participant.identity, publication.trackName]];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
    unpublishedAudioTrack:(TVIRemoteAudioTrackPublication *)publication {
    
    // Remote Participant has stopped sharing the audio Track.
    
    [self logMessage:[NSString stringWithFormat:@"Participant %@ unpublished %@ audio track.",
                      participant.identity, publication.trackName]];
}

- (void)didSubscribeToVideoTrack:(TVIRemoteVideoTrack *)videoTrack
                   publication:(TVIRemoteVideoTrackPublication *)publication
                forParticipant:(TVIRemoteParticipant *)participant {
    
    // We are subscribed to the remote Participant's audio Track. We will start receiving the
    // remote Participant's video frames now.
    [self logMessage:[NSString stringWithFormat:@"%lu remote participants when subscribing to video track for Participant %@", self.remoteParticipants.count, participant.identity]];
    [self logMessage:[NSString stringWithFormat:@"Subscribed to %@ video track for Participant %@",
                      publication.trackName, participant.identity]];
    [[TwilioVideoManager getInstance] publishEvent: VIDEO_TRACK_ADDED];
    [self updateRemoteVideoTrack:videoTrack toParticipant:participant action:@"add"];
    
}

- (void)didUnsubscribeFromVideoTrack:(TVIRemoteVideoTrack *)videoTrack
                       publication:(TVIRemoteVideoTrackPublication *)publication
                    forParticipant:(TVIRemoteParticipant *)participant {
    
    // We are unsubscribed from the remote Participant's video Track. We will no longer receive the
    // remote Participant's video.
    
    [self logMessage:[NSString stringWithFormat:@"Unsubscribed from %@ video track for Participant %@",
                      publication.trackName, participant.identity]];
    [[TwilioVideoManager getInstance] publishEvent: VIDEO_TRACK_REMOVED];
    
    [self updateRemoteVideoTrack:videoTrack toParticipant:participant action:@"remove"];
}

- (void)didSubscribeToAudioTrack:(TVIRemoteAudioTrack *)audioTrack
                   publication:(TVIRemoteAudioTrackPublication *)publication
                forParticipant:(TVIRemoteParticipant *)participant {
    
    // We are subscribed to the remote Participant's audio Track. We will start receiving the
    // remote Participant's audio now.
    
    [self logMessage:[NSString stringWithFormat:@"Subscribed to %@ audio track for Participant %@",
                      publication.trackName, participant.identity]];
    [[TwilioVideoManager getInstance] publishEvent: AUDIO_TRACK_ADDED];
}

- (void)didUnsubscribeFromAudioTrack:(TVIRemoteAudioTrack *)audioTrack
                       publication:(TVIRemoteAudioTrackPublication *)publication
                    forParticipant:(TVIRemoteParticipant *)participant {
    
    // We are unsubscribed from the remote Participant's audio Track. We will no longer receive the
    // remote Participant's audio.
    
    [self logMessage:[NSString stringWithFormat:@"Unsubscribed from %@ audio track for Participant %@",
                      publication.trackName, participant.identity]];
    [[TwilioVideoManager getInstance] publishEvent: AUDIO_TRACK_REMOVED];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
        enabledVideoTrack:(TVIRemoteVideoTrackPublication *)publication {
    [self logMessage:[NSString stringWithFormat:@"Participant %@ enabled %@ video track.",
                      participant.identity, publication.trackName]];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
       disabledVideoTrack:(TVIRemoteVideoTrackPublication *)publication {
    [self logMessage:[NSString stringWithFormat:@"Participant %@ disabled %@ video track.",
                      participant.identity, publication.trackName]];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
        enabledAudioTrack:(TVIRemoteAudioTrackPublication *)publication {
    [self logMessage:[NSString stringWithFormat:@"Participant %@ enabled %@ audio track.",
                      participant.identity, publication.trackName]];
}

- (void)remoteParticipant:(TVIRemoteParticipant *)participant
       disabledAudioTrack:(TVIRemoteAudioTrackPublication *)publication {
    [self logMessage:[NSString stringWithFormat:@"Participant %@ disabled %@ audio track.",
                      participant.identity, publication.trackName]];
}

- (void)failedToSubscribeToAudioTrack:(TVIRemoteAudioTrackPublication *)publication
                                error:(NSError *)error
                       forParticipant:(TVIRemoteParticipant *)participant {
    [self logMessage:[NSString stringWithFormat:@"Participant %@ failed to subscribe to %@ audio track.",
                      participant.identity, publication.trackName]];
}

- (void)failedToSubscribeToVideoTrack:(TVIRemoteVideoTrackPublication *)publication
                                error:(NSError *)error
                       forParticipant:(TVIRemoteParticipant *)participant {
    [self logMessage:[NSString stringWithFormat:@"Participant %@ failed to subscribe to %@ video track.",
                      participant.identity, publication.trackName]];
}

#pragma mark - TVIVideoViewDelegate

- (void)videoView:(TVIVideoView *)view videoDimensionsDidChange:(CMVideoDimensions)dimensions {
    NSLog(@"Dimensions changed to: %d x %d", dimensions.width, dimensions.height);
    [self.view setNeedsLayout];
}

#pragma mark - TVICameraSourceDelegate

- (void)cameraSource:(TVICameraSource *)source didFailWithError:(NSError *)error {
    [self logMessage:[NSString stringWithFormat:@"Capture failed with error.\ncode = %lu error = %@", error.code, error.localizedDescription]];
}

//#pragma mark - TVICameraCapturerDelegate
//
//- (void)cameraCapturer:(TVICameraCapturer *)capturer didStartWithSource:(TVICameraCaptureSource)source {
//    self.previewView.mirror = (source == TVICameraCaptureSourceFrontCamera);
//}

@end
