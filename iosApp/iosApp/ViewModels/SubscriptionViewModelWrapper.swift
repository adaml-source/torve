import Foundation
import shared

final class SubscriptionViewModelWrapper: ObservableObject {
    let viewModel: SubscriptionViewModel
    @Published var state: SubscriptionUiState

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
    func purchase(token: String) { viewModel.purchase(purchaseToken: token) }
    func restorePurchase(token: String) { viewModel.restorePurchase(purchaseToken: token) }
    func checkAccess(feature: PremiumFeature) -> Bool { viewModel.checkAccess(feature: feature) }
    func dismissPaywall() { viewModel.dismissPaywall() }
    func updateRebateCode(_ code: String) { viewModel.updateRebateCode(code: code) }
    func redeemCode() { viewModel.redeemCode() }
}
