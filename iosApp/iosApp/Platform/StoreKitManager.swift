import Foundation
import StoreKit

@MainActor
final class StoreKitManager: ObservableObject {
    @Published var products: [Product] = []
    @Published var purchasedProductIds: Set<String> = []
    @Published var isPurchasing = false

    static let monthlyId = "com.streamvault.pro.monthly"
    static let lifetimeId = "com.streamvault.pro.lifetime"

    init() {
        Task { await loadProducts() }
        Task { await listenForTransactions() }
    }

    func loadProducts() async {
        do {
            products = try await Product.products(for: [Self.monthlyId, Self.lifetimeId])
        } catch { print("Failed to load products: \(error)") }
    }

    func purchase(_ product: Product) async -> Bool {
        isPurchasing = true
        defer { isPurchasing = false }
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                let transaction = try checkVerified(verification)
                purchasedProductIds.insert(transaction.productID)
                await transaction.finish()
                return true
            case .userCancelled, .pending:
                return false
            @unknown default:
                return false
            }
        } catch { return false }
    }

    func restorePurchases() async {
        try? await AppStore.sync()
        for await result in Transaction.currentEntitlements {
            if let transaction = try? checkVerified(result) {
                purchasedProductIds.insert(transaction.productID)
            }
        }
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
