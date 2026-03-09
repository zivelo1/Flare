import Foundation
import Combine

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var conversations: [Conversation] = []
    @Published var currentMessages: [ChatMessage] = []
    @Published var currentContact: Contact?
    @Published var meshStatus = MeshStatus()
    @Published var isServiceRunning = false

    private var cancellables = Set<AnyCancellable>()
    private let repo = FlareRepository.shared
    private let meshService = MeshService.shared

    init() {
        meshService.$meshStatus
            .receive(on: DispatchQueue.main)
            .assign(to: &$meshStatus)

        meshService.$isRunning
            .receive(on: DispatchQueue.main)
            .assign(to: &$isServiceRunning)

        repo.$contacts
            .receive(on: DispatchQueue.main)
            .sink { [weak self] contacts in
                self?.rebuildConversations(from: contacts)
            }
            .store(in: &cancellables)

        meshService.incomingDelivered
            .receive(on: DispatchQueue.main)
            .sink { [weak self] delivered in
                self?.onIncomingMessage(senderId: delivered.senderId, plaintext: delivered.plaintext)
            }
            .store(in: &cancellables)
    }

    func loadConversation(_ conversationId: String) {
        currentContact = repo.contacts.first { $0.identity.deviceId == conversationId }
        do {
            currentMessages = try repo.getMessagesForConversation(conversationId)
        } catch {
            currentMessages = []
        }
    }

    func sendMessage(conversationId: String, text: String) {
        guard let contact = repo.contacts.first(where: { $0.identity.deviceId == conversationId }) else { return }

        let optimistic = ChatMessage(
            messageId: UUID().uuidString,
            conversationId: conversationId,
            senderDeviceId: repo.getDeviceId(),
            content: text,
            timestamp: Date(),
            isOutgoing: true,
            deliveryStatus: .pending
        )
        currentMessages.append(optimistic)

        Task {
            do {
                let serialized = try repo.sendMessage(
                    recipientDeviceId: conversationId,
                    recipientAgreementKey: contact.identity.agreementPublicKey,
                    plaintext: text
                )
                meshService.enqueueOutbound(recipientDeviceId: conversationId, data: serialized)

                if let idx = currentMessages.firstIndex(where: { $0.messageId == optimistic.messageId }) {
                    currentMessages[idx] = ChatMessage(
                        messageId: optimistic.messageId,
                        conversationId: conversationId,
                        senderDeviceId: optimistic.senderDeviceId,
                        content: text,
                        timestamp: optimistic.timestamp,
                        isOutgoing: true,
                        deliveryStatus: .sent
                    )
                }
            } catch {
                if let idx = currentMessages.firstIndex(where: { $0.messageId == optimistic.messageId }) {
                    currentMessages[idx] = ChatMessage(
                        messageId: optimistic.messageId,
                        conversationId: conversationId,
                        senderDeviceId: optimistic.senderDeviceId,
                        content: text,
                        timestamp: optimistic.timestamp,
                        isOutgoing: true,
                        deliveryStatus: .failed
                    )
                }
            }
        }
    }

    private func onIncomingMessage(senderId: String, plaintext: String) {
        let msg = ChatMessage(
            messageId: UUID().uuidString,
            conversationId: senderId,
            senderDeviceId: senderId,
            content: plaintext,
            timestamp: Date(),
            isOutgoing: false,
            deliveryStatus: .delivered
        )

        if currentContact?.identity.deviceId == senderId {
            currentMessages.append(msg)
        }

        rebuildConversations(from: repo.contacts)
    }

    private func rebuildConversations(from contacts: [Contact]) {
        conversations = contacts.map { contact in
            let lastMsg = try? repo.getMessagesForConversation(contact.identity.deviceId).last
            return Conversation(
                id: contact.identity.deviceId,
                contact: contact,
                lastMessage: lastMsg,
                unreadCount: 0
            )
        }.sorted { a, b in
            (a.lastMessage?.timestamp ?? .distantPast) > (b.lastMessage?.timestamp ?? .distantPast)
        }
    }
}
