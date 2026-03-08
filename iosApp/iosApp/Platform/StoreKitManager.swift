import Foundation
import StoreKit

@MainActor
final class StoreKitManager: ObservableObject {
    @Published var products: [Product] = []
    @Published var purchasedProductIds: Set<String> = []
    @Published var isPurchasing = false
    @Published var lastTransactionJWS: String? = nil

    static let monthlyId = "com.torve.pro.monthly"
    static let lifetimeId = "com.torve.pro.lifetime"

    init() {
        Task { await loadProducts() }
        Task { await listenForTransactions() }
    }

    func loadProducts() async {
        do {
            products = try await Product.products(for: [Self.monthlyId, Self.lifetimeId])
        } catch { print("Failed to load products: \(error)") }
    }

    func purchase(_ product: Product) async -> (success: Bool, jwsRepresentation: String?) {
        isPurchasing = true
        defer { isPurchasing = false }
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                let transaction = try checkVerified(verification)
                purchasedProductIds.insert(transaction.productID)

                // Get the JWS representation for backend verification
                let jws: String?
                switch verification {
                case .verified(_):
                    jws = verification.jwsRepresentation
                case .unverified(_, _):
                    jws = nil
                }

                lastTransactionJWS = jws
                await transaction.finish()
                return (true, jws)
            case .userCancelled, .pending:
                return (false, nil)
            @unknown default:
                return (false, nil)
            }
        } catch { return (false, nil) }
    }

    func restorePurchases() async {
        try? await AppStore.sync()
        for await result in Transaction.currentEntitlements {
            if let transaction = try? checkVerified(result) {
                purchasedProductIds.insert(transaction.productID)
            }
        }
    }

    /// Get JWS for the most recent transaction of a given product, for backend verification
    func getLatestTransactionJWS(for productId: String) async -> String? {
        guard let result = await Transaction.latest(for: productId) else { return nil }
        return result.jwsRepresentation
    }

    var isPro: Bool {
        !purchasedProductIds.isEmpty
    }

    private func listenForTransactions() async {
        for await result in Transaction.updates {
            if let transaction = try? checkVerified(result) {
                purchasedProductIds.insert(transaction.productID)
                await transaction.finish()
            }
        }
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified: throw StoreError.failedVerification
        case .verified(let safe): return safe
        }
    }
}

enum StoreError: Error {
    case failedVerification
}
