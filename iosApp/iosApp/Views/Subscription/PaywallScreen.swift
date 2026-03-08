import SwiftUI
import StoreKit
import shared

struct PaywallScreen: View {
    @StateObject private var wrapper = SubscriptionViewModelWrapper()
    @StateObject private var store = StoreKitManager()
    @Environment(AppRouter.self) private var router

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Header
                headerSection

                // Feature comparison
                featureComparisonSection

                // Pricing cards
                pricingSection

                // Subscribe button
                subscribeButton

                // Restore
                restoreButton

                // Rebate code (if enabled)
                if wrapper.state.rebateCodesEnabled {
                    rebateSection
                }

                // Status messages
                statusSection
            }
            .padding()
        }
        .navigationTitle("Torve Pro")
        .onChange(of: wrapper.state.showDeviceLimitReached) { _, show in
            if show {
                wrapper.dismissDeviceLimitReached()
                router.navigate(to: .deviceLimitReached)
            }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(spacing: 12) {
            Image(systemName: "star.circle.fill")
                .font(.system(size: 56))
                .foregroundColor(SVColor.amber)

            Text("Torve Pro")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Unlock the full Torve experience")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)

            if wrapper.state.isPro {
                Label("Active", systemImage: "checkmark.seal.fill")
                    .font(.headline)
                    .foregroundColor(SVColor.emerald)
                    .padding(.top, 4)
            }
        }
    }

    // MARK: - Feature Comparison

    private var featureComparisonSection: some View {
        VStack(spacing: 0) {
            // Header row
            HStack {
                Text("Feature")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("Free")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .frame(width: 50)
                Text("Pro")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .frame(width: 50)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(SVColor.surfaceVariant)

            Divider()

            FeatureRow(feature: "Search & Browse", free: true, pro: true)
            FeatureRow(feature: "Stream Playback", free: false, pro: true)
            FeatureRow(feature: "Downloads", free: false, pro: true)
            FeatureRow(feature: "Live Channels", free: false, pro: true)
            FeatureRow(feature: "Multi-Cloud", free: false, pro: true)
            FeatureRow(feature: "Advanced Filters", free: false, pro: true)
        }
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.secondarySystemBackground))
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Pricing

    private var pricingSection: some View {
        VStack(spacing: 12) {
            if store.products.isEmpty {
                // Store metadata not yet loaded — show loading state, not a fake price
                PricingCard(
                    title: "Lifetime",
                    price: "Loading…",
                    description: "Fetching price from store…",
                    highlighted: true,
                    selected: false,
                    onTap: {}
                )
            } else {
                ForEach(store.products.sorted(by: { $0.price < $1.price }), id: \.id) { product in
                    let isLifetime = product.id == StoreKitManager.lifetimeId
                    PricingCard(
                        title: isLifetime ? "Lifetime" : "Monthly",
                        price: product.displayPrice + (isLifetime ? "" : "/mo"),
                        description: isLifetime ? "One-time payment" : "Cancel anytime",
                        highlighted: isLifetime,
                        selected: false,
                        onTap: {
                            Task {
                                let (success, jws) = await store.purchase(product)
                                if success {
                                    if let jws {
                                        // Send JWS to backend for verification
                                        wrapper.verifyApplePurchase(transactionJws: jws, productId: product.id)
                                    } else {
                                        // Fallback to local-only activation
                                        wrapper.purchase(token: product.id)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // MARK: - Subscribe Button

    private var subscribeButton: some View {
        Group {
            if !wrapper.state.isPro {
                Button(action: {
                    // Purchase the lifetime product if available, otherwise first product
                    guard let product = store.products.first(where: { $0.id == StoreKitManager.lifetimeId })
                            ?? store.products.first else { return }
                    Task {
                        let (success, jws) = await store.purchase(product)
                        if success {
                            if let jws {
                                wrapper.verifyApplePurchase(transactionJws: jws, productId: product.id)
                            } else {
                                wrapper.purchase(token: product.id)
                            }
                        }
                    }
                }) {
                    HStack {
                        if store.isPurchasing || wrapper.state.isPurchasing {
                            ProgressView()
                                .tint(.black)
                        }
                        Text("Subscribe Now")
                    }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(SVColor.amber)
                    .foregroundColor(.black)
                    .fontWeight(.semibold)
                    .cornerRadius(12)
                }
                .disabled(store.isPurchasing || wrapper.state.isPurchasing)
            }
        }
    }

    // MARK: - Restore

    private var restoreButton: some View {
        Button("Restore Purchases") {
            Task {
                await store.restorePurchases()
                if store.isPro {
                    // Try to get JWS for the lifetime purchase to send to backend
                    if let jws = await store.getLatestTransactionJWS(for: StoreKitManager.lifetimeId) {
                        wrapper.verifyApplePurchase(transactionJws: jws, productId: StoreKitManager.lifetimeId)
                    } else if let jws = await store.getLatestTransactionJWS(for: StoreKitManager.monthlyId) {
                        wrapper.verifyApplePurchase(transactionJws: jws, productId: StoreKitManager.monthlyId)
                    } else {
                        wrapper.restorePurchase(token: "restored")
                    }
                }
            }
        }
        .font(.subheadline)
        .foregroundColor(SVColor.onSurfaceVariant)
    }

    // MARK: - Rebate Code

    private var rebateSection: some View {
        VStack(spacing: 12) {
            Divider()

            Text("Have a code?")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)

            HStack {
                TextField("Rebate code", text: Binding(
                    get: { wrapper.state.rebateCode },
                    set: { wrapper.updateRebateCode($0) }
                ))
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)

                Button(action: { wrapper.redeemCode() }) {
                    if wrapper.state.isRedeeming {
                        ProgressView()
                    } else {
                        Text("Redeem")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(SVColor.amber)
                .foregroundColor(.black)
                .cornerRadius(8)
                .disabled(wrapper.state.rebateCode.isEmpty || wrapper.state.isRedeeming)
            }

            if wrapper.state.rebateSuccess {
                Label("Code redeemed successfully!", systemImage: "checkmark.circle.fill")
                    .foregroundColor(SVColor.emerald)
                    .font(.caption)
            }
        }
    }

    // MARK: - Status

    private var statusSection: some View {
        Group {
            if let error = wrapper.state.error {
                Text(error)
                    .font(.caption)
                    .foregroundColor(SVColor.error)
                    .multilineTextAlignment(.center)
            }
        }
    }
}

// MARK: - Feature Row

private struct FeatureRow: View {
    let feature: String
    let free: Bool
    let pro: Bool

    var body: some View {
        HStack {
            Text(feature)
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: free ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundColor(free ? SVColor.emerald : SVColor.error)
                .frame(width: 50)
            Image(systemName: pro ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundColor(pro ? SVColor.emerald : SVColor.error)
                .frame(width: 50)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }
}

// MARK: - Pricing Card

private struct PricingCard: View {
    let title: String
    let price: String
    let description: String
    let highlighted: Bool
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(title)
                            .fontWeight(.bold)
                            .foregroundColor(SVColor.onSurface)
                        if highlighted {
                            Text("BEST VALUE")
                                .font(.caption2)
                                .fontWeight(.bold)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(SVColor.amber)
                                .foregroundColor(.black)
                                .cornerRadius(4)
                        }
                    }
                    Text(description)
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
                Spacer()
                Text(price)
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.amber)
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.secondarySystemBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(highlighted ? SVColor.amber : Color.clear, lineWidth: 2)
            )
        }
    }
}
