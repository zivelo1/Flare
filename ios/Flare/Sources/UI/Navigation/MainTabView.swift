import SwiftUI

struct MainTabView: View {
    @StateObject private var chatVM = ChatViewModel()
    @StateObject private var contactsVM = ContactsViewModel()
    @StateObject private var networkVM = NetworkViewModel()
    @StateObject private var discoveryVM = DiscoveryViewModel()

    init() {
        MeshService.shared.start()
    }

    var body: some View {
        TabView {
            NavigationStack {
                ConversationListView(viewModel: chatVM)
            }
            .tabItem {
                Label("Chats", systemImage: "bubble.left.and.bubble.right")
            }

            NavigationStack {
                ContactsView(
                    viewModel: contactsVM,
                    discoveryVM: discoveryVM
                )
            }
            .tabItem {
                Label("Contacts", systemImage: "person.2")
            }

            NavigationStack {
                NetworkView(viewModel: networkVM)
            }
            .tabItem {
                Label("Network", systemImage: "antenna.radiowaves.left.and.right")
            }
        }
        .environmentObject(chatVM)
    }
}
