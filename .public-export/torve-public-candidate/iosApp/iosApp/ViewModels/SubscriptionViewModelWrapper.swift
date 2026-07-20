import Foundation
import shared

final class SubscriptionViewModelWrapper: ObservableObject {
    let viewModel: SubscriptionViewModel
    @Published var state: SubscriptionUiState
    @Published var fullProductAccessAvailable = true
    @Published var checkoutRequired = false
    @Published var restoreRequired = false
    @Published var subscriptionRequired = false

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.subscriptionViewModel()
        self.state = viewModel.state.value as! SubscriptionUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? SubscriptionUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func loadSubscription() { viewModel.loadSubscription() }
    @available(*, deprecated, message: "Torve no longer uses purchases for access.")
    func purchase(token: String) {}
    @available(*, deprecated, message: "Torve no longer requires purchase restore for access.")
    func restorePurchase(token: String) {}
    func checkAccess(feature: PremiumFeature) -> Bool { true }
    func dismissPaywall() { viewModel.dismissPaywall() }
    @available(*, deprecated, message: "Rebate codes no longer unlock product features.")
    func updateRebateCode(_ code: String) {}
    @available(*, deprecated, message: "Rebate codes no longer unlock product features.")
    func redeemCode() {}

    /// Deprecated compatibility surface. StoreKit verification no longer affects access.
    @available(*, deprecated, message: "Apple purchases no longer control access.")
    func verifyApplePurchase(transactionJws: String, productId: String) {
    }

    func dismissDeviceLimitReached() { viewModel.dismissDeviceLimitReached() }
}
