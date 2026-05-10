{{- define "payment-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "payment-service.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "payment-service.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "payment-service.labels" -}}
helm.sh/chart: {{ include "payment-service.chart" . }}
{{ include "payment-service.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mini-shop
app.kubernetes.io/component: payment
{{- end }}

{{- define "payment-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "payment-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "payment-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "payment-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "payment-service.image" -}}
{{- $repo := "" -}}
{{- if .Values.image.repository -}}
  {{- $repo = .Values.image.repository -}}
{{- else -}}
  {{- $repo = printf "%s/%s" .Values.global.image.registry (include "payment-service.name" .) -}}
{{- end -}}
{{- $tag := .Values.image.tag | default .Values.global.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end }}

{{- define "payment-service.imagePullPolicy" -}}
{{- .Values.image.pullPolicy | default .Values.global.image.pullPolicy | default "IfNotPresent" -}}
{{- end }}

{{- define "payment-service.configMapName" -}}
{{- printf "%s-config" (include "payment-service.fullname" .) -}}
{{- end }}

{{- define "payment-service.secretName" -}}
{{- printf "%s-secret" (include "payment-service.fullname" .) -}}
{{- end }}
