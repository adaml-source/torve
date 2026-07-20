import Foundation

@MainActor
@available(*, deprecated, message: "Torve no longer uses StoreKit for feature access.")
final class StoreKitManager: ObservableObject {
    @Published var isPurchasing = false
    @Published var lastTransactionJWS: String? = nil

    static let showDonationLinks = false
    static let donationURL = ""

    init() {}

    func loadProducts() async {
        isPurchasing = false
    }

    func restorePurchases() async {
        isPurchasing = false
        lastTransactionJWS = nil
    }

    func getLatestTransactionJWS(for productId: String) async -> String? {
        nil
    }

    var isPro: Bool {
        true
    }
}
