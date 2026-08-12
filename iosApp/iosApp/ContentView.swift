import UIKit
import SwiftUI
import Shared

final class IosOrientationController: NSObject, OrientationController {
    weak var viewController: UIViewController?

    func apply(orientation: DashOrientation) {
        DispatchQueue.main.async {
            let mask: UIInterfaceOrientationMask =
                orientation == DashOrientation.landscape ? .landscapeRight : .portrait

            AppDelegate.orientationMask = mask
            self.viewController?.setNeedsUpdateOfSupportedInterfaceOrientations()

            guard let scene = self.viewController?.view.window?.windowScene
                ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first
            else {
                return
            }

            if #available(iOS 16.0, *) {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { error in
                    NSLog("LapSight orientation request failed: \(error.localizedDescription)")
                }
            } else {
                UIDevice.current.setValue(
                    mask == .portrait
                        ? UIInterfaceOrientation.portrait.rawValue
                        : UIInterfaceOrientation.landscapeRight.rawValue,
                    forKey: "orientation"
                )
                UIViewController.attemptRotationToDeviceOrientation()
            }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    private let orientationController = IosOrientationController()

    func makeUIViewController(context: Self.Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(
            orientationController: orientationController
        )
        orientationController.viewController = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
