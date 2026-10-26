// S0 iPhone benchmark host (ADR-0002 §16.1). Runs one benchmark per launch on a background thread and
// writes its output to Documents/<mode>.txt; `DONE` marks completion. Launch argument: bench | dombench.
#import <UIKit/UIKit.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/utsname.h>

int s0_bench_main(int argc, char **argv);
int s0_dombench_main(int argc, char **argv);

static NSString *gMode;

static void *run(void *unused) {
    (void)unused;
    NSBundle *b = NSBundle.mainBundle;
    NSString *docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    NSString *outPath = [docs stringByAppendingPathComponent:[gMode stringByAppendingString:@".txt"]];
    freopen(outPath.fileSystemRepresentation, "w", stdout);
    freopen([docs stringByAppendingPathComponent:@"stderr.txt"].fileSystemRepresentation, "w", stderr);
    setvbuf(stdout, NULL, _IOLBF, 0);
    struct utsname u;
    uname(&u);
    printf("DEVICE %s %s\n", u.machine, UIDevice.currentDevice.systemVersion.UTF8String);
    int rc;
    if ([gMode isEqualToString:@"dombench"]) {
        char *argv[] = { "dombench",
            (char *)[b pathForResource:@"dombench" ofType:@"wasm"].fileSystemRepresentation,
            (char *)[b pathForResource:@"manhuagui-home" ofType:@"html"].fileSystemRepresentation,
            (char *)[b pathForResource:@"dm5-home" ofType:@"html"].fileSystemRepresentation, NULL };
        rc = s0_dombench_main(4, argv);
    } else {
        setenv("SW_WASM", [b pathForResource:@"shinsou_sandbox" ofType:@"wasm"].fileSystemRepresentation, 1);
        char *argv[] = { "bench", (char *)[b pathForResource:@"bootstrap" ofType:@"js"].fileSystemRepresentation, "3", NULL };
        rc = s0_bench_main(3, argv);
    }
    printf("DONE rc=%d\n", rc);
    fflush(stdout);
    return NULL;
}

// iOS 27 SDK apps must adopt the UIScene lifecycle; a window created from the app delegate traps
// in _UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption before the benchmark runs.
@interface SceneDelegate : UIResponder <UIWindowSceneDelegate>
@property (strong, nonatomic) UIWindow *window;
@end

@implementation SceneDelegate
- (void)scene:(UIScene *)scene willConnectToSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    UIWindowScene *ws = (UIWindowScene *)scene;
    self.window = [[UIWindow alloc] initWithWindowScene:ws];
    UIViewController *vc = [UIViewController new];
    UILabel *label = [[UILabel alloc] initWithFrame:vc.view.bounds];
    label.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    label.text = [NSString stringWithFormat:@"S0 benchmark: %@\nKeep this app in front.", gMode];
    label.numberOfLines = 0;
    label.textAlignment = NSTextAlignmentCenter;
    vc.view.backgroundColor = UIColor.systemBackgroundColor;
    [vc.view addSubview:label];
    self.window.rootViewController = vc;
    [self.window makeKeyAndVisible];
}
@end

@interface AppDelegate : UIResponder <UIApplicationDelegate>
@end

@implementation AppDelegate
- (BOOL)application:(UIApplication *)app didFinishLaunchingWithOptions:(NSDictionary *)opts {
    app.idleTimerDisabled = YES; // auto-lock would suspend the run
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16u << 20);
    pthread_t t;
    pthread_create(&t, &attr, run, NULL);
    return YES;
}

- (UISceneConfiguration *)application:(UIApplication *)app
    configurationForConnectingSceneSession:(UISceneSession *)session
                                   options:(UISceneConnectionOptions *)options {
    UISceneConfiguration *c = [[UISceneConfiguration alloc] initWithName:@"Default" sessionRole:session.role];
    c.delegateClass = SceneDelegate.class;
    return c;
}
@end

int main(int argc, char *argv[]) {
    gMode = @"bench";
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "dombench")) gMode = @"dombench";
    }
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(AppDelegate.class));
    }
}
