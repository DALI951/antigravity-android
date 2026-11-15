package com.antigravity.android.network

// Typealiases bridging to production data/network/domain packages
typealias ConnectionStatus = com.antigravity.android.data.network.ConnectionStatus
typealias BackoffCalculator = com.antigravity.android.data.network.BackoffCalculator
typealias OfflineBannerState = com.antigravity.android.data.network.OfflineBannerState
typealias ConnectFrameBuilder = com.antigravity.android.data.network.ConnectFrameBuilder
typealias ConnectFrameEnvelope = com.antigravity.android.data.network.ConnectFrameEnvelope
typealias ConnectError = com.antigravity.android.data.network.ConnectError
typealias ConnectBinaryFrame = com.antigravity.android.data.network.ConnectBinaryFrame

typealias SingularValue = com.antigravity.android.data.model.SingularValue
typealias RepeatedDiff = com.antigravity.android.data.model.RepeatedDiff
typealias MapDiff = com.antigravity.android.data.model.MapDiff
typealias FieldDiff = com.antigravity.android.data.model.FieldDiff
typealias MessageDiff = com.antigravity.android.data.model.MessageDiff
typealias StateDiffReducer = com.antigravity.android.domain.StateDiffReducer
